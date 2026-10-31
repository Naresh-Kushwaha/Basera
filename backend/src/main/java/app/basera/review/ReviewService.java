package app.basera.review;

import static app.basera.common.db.Db.p;

import app.basera.common.audit.AuditService;
import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.db.StudentGate;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import app.basera.notification.NotificationService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interactions (a student's confirmed stay / mess subscription / trial meal) and the reviews they unlock.
 * Defined process: a review needs an interaction CONFIRMED by the owner (or overridden by an admin) AND a minimum elapsed time
 * (stay 14 days, mess subscription 7 days, trial meal 0). This blocks drive-by reviews; it cannot prove a review is honest.
 */
@Service
public class ReviewService {

    private static final Set<String> STAY_KINDS = Set.of("ROOM", "PG", "HOSTEL");
    private static final Map<String, Integer> MIN_DAYS = Map.of("STAY", 14, "MESS_SUBSCRIPTION", 7, "TRIAL_MEAL", 0);

    private final Db db;
    private final Modules modules;
    private final StudentGate gate;
    private final NotificationService notifications;
    private final AuditService audit;

    public ReviewService(Db db, Modules modules, StudentGate gate, NotificationService notifications, AuditService audit) {
        this.db = db;
        this.modules = modules;
        this.gate = gate;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ---- interactions ---------------------------------------------------------------------------------------------

    @Transactional
    public UUID requestInteraction(UUID listingId, String kind, LocalDate startDate) {
        Map<String, Object> l = db.require("""
                SELECT l.kind, l.title, l.owner_user_id AS "ownerUserId"
                FROM listing l JOIN listing_tenant_visibility v ON v.listing_id = l.id AND v.tenant_id = app_current_tenant()
                WHERE l.id = :id AND v.status = 'APPROVED' AND l.active""", p("id", listingId), "LISTING_NOT_FOUND", "Listing not found");
        boolean stay = STAY_KINDS.contains((String) l.get("kind"));
        if (stay != "STAY".equals(kind)) {
            throw ApiException.badRequest("KIND_MISMATCH", stay ? "Use STAY for rooms, PGs and hostels"
                    : "Use MESS_SUBSCRIPTION or TRIAL_MEAL for mess/tiffin");
        }
        UUID id;
        try {
            id = db.id("""
                    INSERT INTO listing_interaction (tenant_id, listing_id, student_membership_id, kind, start_date)
                    VALUES (app_current_tenant(), :l, :me, :kind, :start) RETURNING id""",
                    p("l", listingId, "me", Ctx.membershipId(), "kind", kind, "start", startDate));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("INTERACTION_EXISTS", "You already have an open request of this kind for this listing");
        }
        db.one("SELECT id FROM membership WHERE tenant_id = app_current_tenant() AND user_id = :u", p("u", l.get("ownerUserId")))
                .ifPresent(m -> notifications.notifyMember((UUID) m.get("id"), "INTERACTION_REQUESTED",
                        "New request for \"" + l.get("title") + "\"", "A student asked you to confirm a " + kind.toLowerCase().replace('_', ' '),
                        Map.of("interactionId", id.toString()), true));
        return id;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine() {
        return db.rows("""
                SELECT i.id, i.kind, i.status, i.start_date AS "startDate", i.confirmed_at AS "confirmedAt", l.id AS "listingId", l.title,
                       EXISTS (SELECT 1 FROM review r WHERE r.interaction_id = i.id) AS reviewed
                FROM listing_interaction i JOIN listing l ON l.id = i.listing_id
                WHERE i.student_membership_id = :me ORDER BY i.created_at DESC""", p("me", Ctx.membershipId()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> forOwner(String status) {
        boolean filter = status != null && !status.isBlank();
        Map<String, Object> params = p("me", Ctx.userId());
        if (filter) {
            params.put("status", status);
        }
        return db.rows("""
                SELECT i.id, i.kind, i.status, i.start_date AS "startDate", i.created_at AS "requestedAt",
                       l.id AS "listingId", l.title, m.display_name AS "studentName", m.photo_url AS "studentPhoto"
                FROM listing_interaction i
                JOIN listing l ON l.id = i.listing_id AND l.owner_user_id = :me
                JOIN membership m ON m.id = i.student_membership_id AND m.tenant_id = i.tenant_id
                WHERE true %s ORDER BY i.created_at DESC""".formatted(filter ? "AND i.status = :status" : ""), params);
    }

    @Transactional
    public void ownerDecide(UUID interactionId, boolean confirm) {
        Map<String, Object> row = db.require("""
                UPDATE listing_interaction i SET status = :s, confirmed_by = :me, confirmed_at = now()
                FROM listing l
                WHERE i.id = :id AND l.id = i.listing_id AND l.owner_user_id = :me AND i.status = 'REQUESTED'
                RETURNING i.student_membership_id AS "student", l.title AS title""",
                p("s", confirm ? "CONFIRMED" : "REJECTED", "me", Ctx.userId(), "id", interactionId),
                "REQUEST_NOT_FOUND", "No pending request found");
        notifyStudent(row, confirm, interactionId);
    }

    /** Requests still waiting for the owner; admins can confirm them (e.g. when an owner does not respond). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forAdmin(String status) {
        return db.rows("""
                SELECT i.id, i.kind, i.status, i.start_date AS "startDate", i.created_at AS "requestedAt", l.id AS "listingId", l.title,
                       m.display_name AS "studentName"
                FROM listing_interaction i JOIN listing l ON l.id = i.listing_id
                JOIN membership m ON m.id = i.student_membership_id AND m.tenant_id = i.tenant_id
                WHERE i.status = :status ORDER BY i.created_at LIMIT 200""",
                p("status", status == null || status.isBlank() ? "REQUESTED" : status));
    }

    @Transactional
    public void adminConfirm(UUID interactionId) {
        Map<String, Object> row = db.require("""
                UPDATE listing_interaction i SET status = 'CONFIRMED', confirmed_by = :me, confirmed_at = now()
                FROM listing l
                WHERE i.id = :id AND l.id = i.listing_id AND i.status = 'REQUESTED'
                RETURNING i.student_membership_id AS "student", l.title AS title""",
                p("me", Ctx.userId(), "id", interactionId), "REQUEST_NOT_FOUND", "No pending request found");
        audit.record("INTERACTION_ADMIN_CONFIRMED", "listing_interaction", interactionId, Map.of());
        notifyStudent(row, true, interactionId);
    }

    private void notifyStudent(Map<String, Object> row, boolean confirmed, UUID interactionId) {
        notifications.notifyMember((UUID) row.get("student"), confirmed ? "INTERACTION_CONFIRMED" : "INTERACTION_REJECTED",
                confirmed ? "Confirmed: \"" + row.get("title") + "\"" : "Request declined: \"" + row.get("title") + "\"",
                confirmed ? "You can review it once the minimum time has passed." : null,
                Map.of("interactionId", interactionId.toString()), true);
    }

    // ---- reviews ----------------------------------------------------------------------------------------------------

    @Transactional
    public UUID createReview(UUID listingId, UUID interactionId, int rating, Integer hygieneRating, String body) {
        modules.require("REVIEWS");
        gate.requireVerifiedIfConfigured();
        Map<String, Object> i = db.require("""
                SELECT i.kind, (current_date - COALESCE(i.start_date, i.confirmed_at::date)) AS days, l.kind AS "listingKind"
                FROM listing_interaction i JOIN listing l ON l.id = i.listing_id
                WHERE i.id = :iid AND i.listing_id = :lid AND i.student_membership_id = :me AND i.status = 'CONFIRMED'""",
                p("iid", interactionId, "lid", listingId, "me", Ctx.membershipId()),
                "INTERACTION_NOT_FOUND", "You can only review something an owner has confirmed with you");
        int needed = MIN_DAYS.get((String) i.get("kind"));
        long days = ((Number) i.get("days")).longValue();
        if (days < needed) {
            throw ApiException.conflict("TOO_EARLY", "You can review this after " + needed + " days (" + (needed - days) + " to go)");
        }
        boolean food = !STAY_KINDS.contains((String) i.get("listingKind"));
        UUID id;
        try {
            id = db.id("""
                    INSERT INTO review (tenant_id, listing_id, interaction_id, author_membership_id, rating, hygiene_rating, body)
                    VALUES (app_current_tenant(), :l, :i, :me, :rating, :hygiene, :body) RETURNING id""",
                    p("l", listingId, "i", interactionId, "me", Ctx.membershipId(), "rating", rating,
                            "hygiene", food ? hygieneRating : null, "body", TextSanitizer.clean(body)));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_REVIEWED", "You have already reviewed this");
        }
        recalc(listingId);
        return id;
    }

    @Transactional
    public void updateReview(UUID reviewId, int rating, Integer hygieneRating, String body) {
        Map<String, Object> r = db.require("SELECT listing_id AS \"listingId\" FROM review WHERE id = :id AND author_membership_id = :me",
                p("id", reviewId, "me", Ctx.membershipId()), "REVIEW_NOT_FOUND", "Review not found");
        db.exec("UPDATE review SET rating = :rating, hygiene_rating = :hygiene, body = :body WHERE id = :id",
                p("rating", rating, "hygiene", hygieneRating, "body", TextSanitizer.clean(body), "id", reviewId));
        recalc((UUID) r.get("listingId"));
    }

    @Transactional
    public void deleteReview(UUID reviewId) {
        Map<String, Object> r = db.require("DELETE FROM review WHERE id = :id AND author_membership_id = :me RETURNING listing_id AS \"listingId\"",
                p("id", reviewId, "me", Ctx.membershipId()), "REVIEW_NOT_FOUND", "Review not found");
        recalc((UUID) r.get("listingId"));
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(UUID listingId, int page, int size) {
        Pageable pg = Paging.of(page, size);
        long total = db.count("SELECT count(*) FROM review WHERE tenant_id = app_current_tenant() AND listing_id = :l AND status = 'PUBLISHED'",
                p("l", listingId));
        List<Map<String, Object>> items = db.rows("""
                SELECT r.id, r.rating, r.hygiene_rating AS "hygieneRating", r.body, r.created_at AS "createdAt",
                       m.display_name AS "authorName", m.photo_url AS "authorPhoto", (r.author_membership_id = :me) AS mine
                FROM review r JOIN membership m ON m.id = r.author_membership_id AND m.tenant_id = r.tenant_id
                WHERE r.tenant_id = app_current_tenant() AND r.listing_id = :l AND r.status = 'PUBLISHED'
                ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset""",
                p("l", listingId, "me", Ctx.membershipId(), "limit", pg.getPageSize(), "offset", (int) pg.getOffset()));
        return PageResponse.of(items, pg.getPageNumber(), pg.getPageSize(), total);
    }

    @Transactional
    public void report(UUID reviewId, String reason) {
        Map<String, Object> r = db.require("SELECT author_membership_id AS author FROM review WHERE id = :id AND status = 'PUBLISHED'",
                p("id", reviewId), "REVIEW_NOT_FOUND", "Review not found");
        if (Ctx.membershipId().equals(r.get("author"))) {
            throw ApiException.badRequest("OWN_REVIEW", "You cannot report your own review");
        }
        try {
            db.exec("""
                    INSERT INTO review_report (tenant_id, review_id, reporter_membership_id, reason)
                    VALUES (app_current_tenant(), :r, :me, :reason)""",
                    p("r", reviewId, "me", Ctx.membershipId(), "reason", TextSanitizer.truncate(TextSanitizer.cleanSingleLine(reason), 500)));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_REPORTED", "You already reported this review");
        }
        notifications.notifyAdmins("REVIEW_REPORTED", "A review was reported", null, Map.of("reviewId", reviewId.toString()));
    }

    // ---- moderation (admin) ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> moderationQueue() {
        return db.rows("""
                SELECT rr.id AS "reportId", rr.reason, rr.created_at AS "reportedAt", r.id AS "reviewId", r.rating, r.body,
                       r.status AS "reviewStatus", l.title AS "listingTitle", m.display_name AS "authorName",
                       (SELECT count(*) FROM review_report x WHERE x.review_id = r.id AND x.status = 'OPEN') AS "openReports"
                FROM review_report rr JOIN review r ON r.id = rr.review_id AND r.tenant_id = rr.tenant_id
                JOIN listing l ON l.id = r.listing_id
                JOIN membership m ON m.id = r.author_membership_id AND m.tenant_id = r.tenant_id
                WHERE rr.status = 'OPEN' ORDER BY rr.created_at""", p());
    }

    @Transactional
    public void moderate(UUID reportId, boolean hide) {
        Map<String, Object> r = db.require("SELECT review_id AS \"reviewId\" FROM review_report WHERE id = :id AND status = 'OPEN'",
                p("id", reportId), "REPORT_NOT_FOUND", "Report not found or already handled");
        UUID reviewId = (UUID) r.get("reviewId");
        if (hide) {
            Map<String, Object> rv = db.require("UPDATE review SET status = 'HIDDEN' WHERE id = :id RETURNING listing_id AS \"listingId\", author_membership_id AS author",
                    p("id", reviewId), "REVIEW_NOT_FOUND", "Review not found");
            db.exec("UPDATE review_report SET status = 'ACTIONED', resolved_by = :me, resolved_at = now() WHERE review_id = :r AND status = 'OPEN'",
                    p("me", Ctx.userId(), "r", reviewId));
            recalc((UUID) rv.get("listingId"));
            notifications.notifyMember((UUID) rv.get("author"), "REVIEW_HIDDEN", "Your review was removed by a moderator",
                    "It was reported and judged to break the community rules.", Map.of(), false);
        } else {
            db.exec("UPDATE review_report SET status = 'DISMISSED', resolved_by = :me, resolved_at = now() WHERE id = :id",
                    p("me", Ctx.userId(), "id", reportId));
        }
        audit.record(hide ? "REVIEW_HIDDEN" : "REVIEW_REPORT_DISMISSED", "review", reviewId, Map.of("reportId", reportId.toString()));
    }

    // ---- aggregates -------------------------------------------------------------------------------------------------------

    private void recalc(UUID listingId) {
        Map<String, Object> params = new HashMap<>();
        params.put("l", listingId);
        db.exec("""
                UPDATE listing_tenant_visibility v SET rating_avg = s.avg, rating_count = s.cnt
                FROM (SELECT round(avg(rating)::numeric, 2) AS avg, count(*)::int AS cnt FROM review
                      WHERE tenant_id = app_current_tenant() AND listing_id = :l AND status = 'PUBLISHED') s
                WHERE v.tenant_id = app_current_tenant() AND v.listing_id = :l""", params);
    }
}
