package app.basera.listing;

import static app.basera.common.db.Db.p;

import app.basera.common.audit.AuditService;
import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import app.basera.notification.NotificationService;
import app.basera.platform.OwnerMembershipProvisioner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TENANT_ADMIN decisions about which listings THEIR students see, and the verification checklist.
 * "Verified" is only ever created here, from a completed four-point checklist, and it expires.
 */
@Service
public class VisibilityAdminService {

    public record Checklist(boolean identityChecked, boolean ownershipChecked, boolean siteVisitDone, boolean termsConfirmed) {
        boolean complete() {
            return identityChecked && ownershipChecked && siteVisitDone && termsConfirmed;
        }
    }

    private final Db db;
    private final AuditService audit;
    private final NotificationService notifications;
    private final OwnerMembershipProvisioner provisioner;

    public VisibilityAdminService(Db db, AuditService audit, NotificationService notifications,
                                  OwnerMembershipProvisioner provisioner) {
        this.db = db;
        this.audit = audit;
        this.notifications = notifications;
        this.provisioner = provisioner;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(String status, int page, int size) {
        Pageable pg = Paging.of(page, size);
        String where = status == null || status.isBlank() ? "" : " AND v.status = :status";
        Map<String, Object> params = new HashMap<>();
        if (!where.isEmpty()) {
            params.put("status", status);
        }
        long total = db.count("SELECT count(*) FROM listing_tenant_visibility v WHERE v.tenant_id = app_current_tenant()" + where, params);
        params.put("limit", pg.getPageSize());
        params.put("offset", (int) pg.getOffset());
        List<Map<String, Object>> items = db.rows("""
                SELECT v.id, v.status, v.status_reason AS "statusReason", v.created_at AS "requestedAt", v.decided_at AS "decidedAt",
                       l.id AS "listingId", l.title, l.kind, l.locality, l.address_line AS "addressLine",
                       l.contact_phone AS "contactPhone",
                       round(ST_Distance(l.geog, t.campus_geog))::int AS "distanceM",
                       %s AS verified
                FROM listing_tenant_visibility v
                JOIN listing l ON l.id = v.listing_id
                JOIN tenant t ON t.id = v.tenant_id
                WHERE v.tenant_id = app_current_tenant() %s
                ORDER BY CASE v.status WHEN 'PENDING' THEN 0 ELSE 1 END, v.created_at DESC
                LIMIT :limit OFFSET :offset""".formatted(ListingQueryService.VERIFIED_EXPR, where), params);
        return PageResponse.of(items, pg.getPageNumber(), pg.getPageSize(), total);
    }

    @Transactional
    public void decide(UUID visibilityId, String target, String reason) {
        Map<String, Object> v = db.require("""
                SELECT v.id, v.status, v.owner_user_id AS "ownerUserId", v.listing_id AS "listingId", l.title
                FROM listing_tenant_visibility v JOIN listing l ON l.id = v.listing_id
                WHERE v.id = :id AND v.tenant_id = app_current_tenant()""",
                p("id", visibilityId), "REQUEST_NOT_FOUND", "Listing request not found");
        if (target.equals(v.get("status"))) {
            throw ApiException.conflict("NO_CHANGE", "The listing is already " + target);
        }
        if (!"APPROVED".equals(target) && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("REASON_REQUIRED", "Give the owner a reason");
        }
        UUID ownerUserId = (UUID) v.get("ownerUserId");
        if ("APPROVED".equals(target)) {
            provisioner.ensureOwner(Ctx.tenantId(), ownerUserId);   // separate platform transaction, committed first
        }
        db.exec("""
                UPDATE listing_tenant_visibility SET status = :s, status_reason = :reason, decided_by = :me, decided_at = now()
                WHERE id = :id AND tenant_id = app_current_tenant()""",
                p("s", target, "reason", "APPROVED".equals(target) ? null : TextSanitizer.cleanSingleLine(reason),
                        "me", Ctx.userId(), "id", visibilityId));
        audit.record("LISTING_" + target, "listing_visibility", visibilityId,
                Map.of("listingId", v.get("listingId").toString(), "reason", reason == null ? "" : reason));
        db.one("SELECT id FROM membership WHERE tenant_id = app_current_tenant() AND user_id = :u", p("u", ownerUserId))
                .ifPresent(m -> notifications.notifyMember((UUID) m.get("id"), "LISTING_" + target,
                        "Your listing \"" + v.get("title") + "\" was " + target.toLowerCase(),
                        reason, Map.of("listingId", v.get("listingId").toString()), true));
    }

    /** Records a completed verification checklist; supersedes any previous active one. Valid for 1-24 months (default 12). */
    @Transactional
    public Map<String, Object> verify(UUID visibilityId, Checklist checklist, String notes, Integer validMonths) {
        if (checklist == null || !checklist.complete()) {
            throw ApiException.badRequest("CHECKLIST_INCOMPLETE",
                    "All four checks must be completed: identity, ownership, site visit and written terms");
        }
        int months = validMonths == null ? 12 : Math.min(Math.max(validMonths, 1), 24);
        Map<String, Object> v = db.require("""
                SELECT v.id, v.status, v.owner_user_id AS "ownerUserId", l.title, l.id AS "listingId"
                FROM listing_tenant_visibility v JOIN listing l ON l.id = v.listing_id
                WHERE v.id = :id AND v.tenant_id = app_current_tenant()""",
                p("id", visibilityId), "REQUEST_NOT_FOUND", "Listing request not found");
        if (!"APPROVED".equals(v.get("status"))) {
            throw ApiException.conflict("NOT_APPROVED", "Approve the listing before verifying it");
        }
        db.exec("UPDATE verification_record SET status = 'SUPERSEDED' WHERE tenant_id = app_current_tenant() AND visibility_id = :id AND status = 'ACTIVE'",
                p("id", visibilityId));
        String checklistJson = "{\"identityChecked\":true,\"ownershipChecked\":true,\"siteVisitDone\":true,\"termsConfirmed\":true}";
        UUID rec = db.id("""
                INSERT INTO verification_record (tenant_id, visibility_id, checklist, notes, verified_by, expires_at)
                VALUES (app_current_tenant(), :id, CAST(:cl AS jsonb), :notes, :me, now() + make_interval(months => :months))
                RETURNING id""", p("id", visibilityId, "cl", checklistJson, "notes", TextSanitizer.clean(notes),
                "me", Ctx.userId(), "months", months));
        audit.record("LISTING_VERIFIED", "listing_visibility", visibilityId, Map.of("months", months));
        db.one("SELECT id FROM membership WHERE tenant_id = app_current_tenant() AND user_id = :u", p("u", v.get("ownerUserId")))
                .ifPresent(m -> notifications.notifyMember((UUID) m.get("id"), "LISTING_VERIFIED",
                        "\"" + v.get("title") + "\" is now verified", "Valid for " + months + " months.",
                        Map.of("listingId", v.get("listingId").toString()), true));
        return Map.of("id", rec, "validMonths", months);
    }
}
