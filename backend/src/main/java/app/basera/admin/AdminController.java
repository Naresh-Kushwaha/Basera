package app.basera.admin;

import static app.basera.common.db.Db.p;

import app.basera.common.audit.AuditService;
import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import app.basera.listing.ListingQueryService;
import app.basera.platform.PlatformService;
import app.basera.platform.dto.UpdateTenantRequest;
import app.basera.tenancy.TenantInfo;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Announcements, analytics, audit log, branding (TENANT_ADMIN) and the owner dashboard. */
@RestController
@RequestMapping("/api/v1")
public class AdminController {

    public record AnnouncementRequest(String title, String body, Boolean pinned, OffsetDateTime expiresAt) {}

    public record BrandingRequest(Map<String, String> branding, List<String> languages, String defaultLanguage,
                                  Boolean requireVerifiedStudent) {}

    private final Db db;
    private final AuditService audit;
    private final PlatformService platform;

    public AdminController(Db db, AuditService audit, PlatformService platform) {
        this.db = db;
        this.audit = audit;
        this.platform = platform;
    }

    // ---- announcements ----------------------------------------------------------------------------------------------

    @GetMapping("/announcements")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> announcements() {
        return db.rows("""
                SELECT id, title, body, pinned, published_at AS "publishedAt", expires_at AS "expiresAt" FROM announcement
                WHERE published_at <= now() AND (expires_at IS NULL OR expires_at > now())
                ORDER BY pinned DESC, published_at DESC LIMIT 20""", p());
    }

    @GetMapping("/admin/announcements")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> allAnnouncements() {
        return db.rows("""
                SELECT id, title, body, pinned, published_at AS "publishedAt", expires_at AS "expiresAt" FROM announcement
                ORDER BY published_at DESC LIMIT 100""", p());
    }

    @PostMapping("/admin/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createAnnouncement(@RequestBody AnnouncementRequest r) {
        validate(r);
        UUID id = db.id("""
                INSERT INTO announcement (tenant_id, title, body, pinned, expires_at)
                VALUES (app_current_tenant(), :title, :body, COALESCE(:pinned, false), :exp) RETURNING id""", params(r));
        audit.record("ANNOUNCEMENT_CREATED", "announcement", id, Map.of());
        return Map.of("id", id);
    }

    @PutMapping("/admin/announcements/{id}")
    @Transactional
    public ResponseEntity<Void> updateAnnouncement(@PathVariable UUID id, @RequestBody AnnouncementRequest r) {
        validate(r);
        Map<String, Object> params = params(r);
        params.put("id", id);
        db.execOrNotFound("UPDATE announcement SET title = :title, body = :body, pinned = COALESCE(:pinned, false), expires_at = :exp WHERE id = :id",
                params, "ANNOUNCEMENT_NOT_FOUND", "Announcement not found");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/announcements/{id}")
    @Transactional
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable UUID id) {
        db.execOrNotFound("DELETE FROM announcement WHERE id = :id", p("id", id), "ANNOUNCEMENT_NOT_FOUND", "Announcement not found");
        audit.record("ANNOUNCEMENT_DELETED", "announcement", id, Map.of());
        return ResponseEntity.noContent().build();
    }

    // ---- analytics --------------------------------------------------------------------------------------------------

    @GetMapping("/admin/analytics/overview")
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>(db.require("""
                SELECT
                  (SELECT count(*) FROM membership WHERE role = 'STUDENT' AND status = 'ACTIVE') AS students,
                  (SELECT count(*) FROM membership WHERE role = 'OWNER' AND status = 'ACTIVE') AS owners,
                  (SELECT count(*) FROM membership WHERE status = 'SUSPENDED') AS "suspendedMembers",
                  (SELECT count(*) FROM membership WHERE created_at > now() - interval '7 days') AS "newMembers7d",
                  (SELECT count(*) FROM listing_tenant_visibility WHERE tenant_id = app_current_tenant() AND status = 'APPROVED') AS "approvedListings",
                  (SELECT count(*) FROM listing_tenant_visibility WHERE tenant_id = app_current_tenant() AND status = 'PENDING') AS "pendingListings",
                  (SELECT count(*) FROM listing_tenant_visibility WHERE tenant_id = app_current_tenant() AND status = 'SUSPENDED') AS "suspendedListings",
                  (SELECT count(*) FROM review WHERE status = 'PUBLISHED') AS reviews,
                  (SELECT round(avg(rating), 2) FROM review WHERE status = 'PUBLISHED') AS "avgRating",
                  (SELECT count(*) FROM listing_interaction WHERE status = 'CONFIRMED') AS "confirmedInteractions",
                  (SELECT count(*) FROM review_report WHERE status = 'OPEN') AS "openReviewReports",
                  (SELECT count(*) FROM scam_report WHERE status = 'OPEN') AS "openScamReports",
                  (SELECT count(*) FROM utility_report WHERE status = 'OPEN') AS "openUtilityReports",
                  (SELECT count(*) FROM issue_report WHERE status <> 'RESOLVED') AS "openIssues",
                  (SELECT count(*) FROM roommate_post WHERE status = 'ACTIVE') AS "activeRoommatePosts"
                """, p(), "NO_DATA", "No data"));
        out.put("verifiedListings", db.count("""
                SELECT count(*) FROM listing_tenant_visibility v JOIN listing l ON l.id = v.listing_id
                WHERE v.tenant_id = app_current_tenant() AND v.status = 'APPROVED' AND %s""".formatted(ListingQueryService.VERIFIED_EXPR), p()));
        return out;
    }

    @GetMapping("/admin/analytics/listings")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listingStats() {
        return db.rows("""
                SELECT l.kind, count(*) AS count, round(avg(COALESCE(sd.rent_monthly, fd.monthly_price))) AS "avgPrice",
                       round(avg(v.rating_avg), 2) AS "avgRating"
                FROM listing l JOIN listing_tenant_visibility v ON v.listing_id = l.id AND v.tenant_id = app_current_tenant() AND v.status = 'APPROVED'
                LEFT JOIN stay_detail sd ON sd.listing_id = l.id LEFT JOIN food_detail fd ON fd.listing_id = l.id
                GROUP BY l.kind ORDER BY l.kind""", p());
    }

    @GetMapping("/admin/analytics/activity")
    @Transactional(readOnly = true)
    public Map<String, Object> activity() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signups", db.rows("""
                SELECT created_at::date AS day, count(*) AS count FROM membership
                WHERE created_at > now() - interval '30 days' GROUP BY 1 ORDER BY 1""", p()));
        out.put("listingRequests", db.rows("""
                SELECT created_at::date AS day, count(*) AS count FROM listing_tenant_visibility
                WHERE tenant_id = app_current_tenant() AND created_at > now() - interval '30 days' GROUP BY 1 ORDER BY 1""", p()));
        out.put("reviews", db.rows("""
                SELECT created_at::date AS day, count(*) AS count FROM review
                WHERE created_at > now() - interval '30 days' GROUP BY 1 ORDER BY 1""", p()));
        return out;
    }

    // ---- audit log / branding ---------------------------------------------------------------------------------------

    @GetMapping("/admin/audit-log")
    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> auditLog(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        Pageable pg = Paging.of(page, size);
        long total = db.count("SELECT count(*) FROM audit_log WHERE tenant_id = app_current_tenant()", p());
        List<Map<String, Object>> items = db.rows("""
                SELECT a.id, a.action, a.target_type AS "targetType", a.target_id AS "targetId", a.details, a.created_at AS "createdAt",
                       m.display_name AS actor
                FROM audit_log a LEFT JOIN membership m ON m.user_id = a.actor_user_id AND m.tenant_id = a.tenant_id
                WHERE a.tenant_id = app_current_tenant() ORDER BY a.created_at DESC LIMIT :limit OFFSET :offset""",
                p("limit", pg.getPageSize(), "offset", (int) pg.getOffset()));
        return PageResponse.of(items, pg.getPageNumber(), pg.getPageSize(), total);
    }

    /** A college admin may change look & feel, languages and the verified-student requirement; modules stay with SUPER_ADMIN. */
    @PutMapping("/admin/tenant/settings")
    public TenantInfo settings(@RequestBody BrandingRequest r) {
        return platform.updateTenant(Ctx.tenantId(), new UpdateTenantRequest(null, null, r.defaultLanguage(), r.languages(),
                r.branding(), null, r.requireVerifiedStudent(), null));
    }

    // ---- owner dashboard --------------------------------------------------------------------------------------------

    @GetMapping("/owner/dashboard")
    @Transactional(readOnly = true)
    public Map<String, Object> ownerDashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listings", db.count("SELECT count(*) FROM listing WHERE owner_user_id = :me", p("me", Ctx.userId())));
        out.put("visibilityByStatus", db.rows("""
                SELECT status, count(*) AS count FROM listing_tenant_visibility WHERE owner_user_id = :me GROUP BY status""", p("me", Ctx.userId())));
        out.put("pendingRequests", db.count("""
                SELECT count(*) FROM listing_interaction i JOIN listing l ON l.id = i.listing_id
                WHERE l.owner_user_id = :me AND i.status = 'REQUESTED'""", p("me", Ctx.userId())));
        out.put("openUtilityReports", db.count("""
                SELECT count(*) FROM utility_report u JOIN listing l ON l.id = u.listing_id
                WHERE l.owner_user_id = :me AND u.status = 'OPEN'""", p("me", Ctx.userId())));
        return out;
    }

    private static void validate(AnnouncementRequest r) {
        if (r.title() == null || r.title().isBlank() || r.title().length() > 150 || r.body() == null || r.body().isBlank() || r.body().length() > 4000) {
            throw ApiException.badRequest("INVALID_ANNOUNCEMENT", "title (max 150) and body (max 4000) are required");
        }
    }

    private static Map<String, Object> params(AnnouncementRequest r) {
        return p("title", TextSanitizer.cleanSingleLine(r.title()), "body", TextSanitizer.clean(r.body()), "pinned", r.pinned(), "exp", r.expiresAt());
    }
}
