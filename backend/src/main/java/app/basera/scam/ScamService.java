package app.basera.scam;

import static app.basera.common.db.Db.p;

import app.basera.common.audit.AuditService;
import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.notification.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scam protection. Students report a listing; when AUTO_SUSPEND_AFTER distinct students have open reports, the listing is
 * hidden from this college automatically until an admin decides. Admins can also suspend an owner outright.
 * This reduces harm; it cannot prove fraud. The admin's decision is the authority.
 */
@Service
public class ScamService {

    static final int AUTO_SUSPEND_AFTER = 3;

    private final Db db;
    private final Modules modules;
    private final NotificationService notifications;
    private final AuditService audit;

    public ScamService(Db db, Modules modules, NotificationService notifications, AuditService audit) {
        this.db = db;
        this.modules = modules;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public void report(UUID listingId, String reason, String details) {
        modules.require("SCAM_PROTECTION");
        Map<String, Object> l = db.require("""
                SELECT l.title, l.owner_user_id AS "ownerUserId" FROM listing l
                JOIN listing_tenant_visibility v ON v.listing_id = l.id AND v.tenant_id = app_current_tenant()
                WHERE l.id = :id""", p("id", listingId), "LISTING_NOT_FOUND", "Listing not found");
        try {
            db.exec("""
                    INSERT INTO scam_report (tenant_id, listing_id, reporter_membership_id, reason, details)
                    VALUES (app_current_tenant(), :l, :me, :reason, :details)""",
                    p("l", listingId, "me", Ctx.membershipId(), "reason", reason,
                            "details", TextSanitizer.truncate(TextSanitizer.clean(details), 1000)));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_REPORTED", "You already reported this listing");
        }
        int suspended = db.exec("""
                UPDATE listing_tenant_visibility SET status = 'SUSPENDED',
                       status_reason = 'Hidden automatically after several scam reports; waiting for admin review'
                WHERE tenant_id = app_current_tenant() AND listing_id = :l AND status = 'APPROVED'
                  AND (SELECT count(DISTINCT reporter_membership_id) FROM scam_report
                       WHERE tenant_id = app_current_tenant() AND listing_id = :l AND status = 'OPEN') >= :threshold""",
                p("l", listingId, "threshold", AUTO_SUSPEND_AFTER));
        notifications.notifyAdmins(suspended > 0 ? "LISTING_AUTO_SUSPENDED" : "SCAM_REPORTED",
                suspended > 0 ? "Listing hidden after repeated scam reports" : "Scam report received",
                "\"" + l.get("title") + "\"", Map.of("listingId", listingId.toString()));
        if (suspended > 0) {
            db.one("SELECT id FROM membership WHERE tenant_id = app_current_tenant() AND user_id = :u", p("u", l.get("ownerUserId")))
                    .ifPresent(m -> notifications.notifyMember((UUID) m.get("id"), "LISTING_SUSPENDED",
                            "\"" + l.get("title") + "\" was hidden pending review",
                            "Several students reported it. The college admin will contact you.", Map.of(), true));
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String st = status == null || status.isBlank() ? "OPEN" : status;
        return db.rows("""
                SELECT s.id, s.reason, s.details, s.status, s.created_at AS "reportedAt", l.id AS "listingId", l.title,
                       l.owner_user_id AS "ownerUserId", m.display_name AS "reporterName", v.status AS "visibilityStatus",
                       (SELECT count(DISTINCT x.reporter_membership_id) FROM scam_report x
                        WHERE x.tenant_id = s.tenant_id AND x.listing_id = s.listing_id AND x.status = 'OPEN') AS "openReporters"
                FROM scam_report s
                JOIN listing l ON l.id = s.listing_id
                JOIN listing_tenant_visibility v ON v.tenant_id = s.tenant_id AND v.listing_id = s.listing_id
                JOIN membership m ON m.id = s.reporter_membership_id AND m.tenant_id = s.tenant_id
                WHERE s.status = :st ORDER BY s.created_at DESC""", p("st", st));
    }

    @Transactional
    public void resolve(UUID reportId, boolean confirm) {
        Map<String, Object> s = db.require("SELECT listing_id AS \"listingId\" FROM scam_report WHERE id = :id AND status = 'OPEN'",
                p("id", reportId), "REPORT_NOT_FOUND", "Report not found or already handled");
        UUID listingId = (UUID) s.get("listingId");
        db.exec("UPDATE scam_report SET status = :st, resolved_by = :me, resolved_at = now() WHERE id = :id",
                p("st", confirm ? "CONFIRMED" : "DISMISSED", "me", Ctx.userId(), "id", reportId));
        if (confirm) {
            db.exec("""
                    UPDATE listing_tenant_visibility SET status = 'SUSPENDED', status_reason = 'Confirmed scam report'
                    WHERE tenant_id = app_current_tenant() AND listing_id = :l AND status <> 'SUSPENDED'""", p("l", listingId));
        } else {
            // dismissed: if it was auto-suspended and no open reports remain, bring it back
            db.exec("""
                    UPDATE listing_tenant_visibility SET status = 'APPROVED', status_reason = NULL
                    WHERE tenant_id = app_current_tenant() AND listing_id = :l AND status = 'SUSPENDED'
                      AND status_reason LIKE 'Hidden automatically%'
                      AND NOT EXISTS (SELECT 1 FROM scam_report WHERE tenant_id = app_current_tenant() AND listing_id = :l AND status = 'OPEN')""",
                    p("l", listingId));
        }
        audit.record(confirm ? "SCAM_CONFIRMED" : "SCAM_DISMISSED", "scam_report", reportId, Map.of("listingId", listingId.toString()));
    }

    /** Flags a suspicious owner for THIS college: suspends their membership and hides all their listings here. */
    @Transactional
    public void suspendOwner(UUID ownerUserId, String reason) {
        int m = db.exec("UPDATE membership SET status = 'SUSPENDED' WHERE user_id = :u AND role = 'OWNER'", p("u", ownerUserId));
        if (m == 0) {
            throw ApiException.notFound("OWNER_NOT_FOUND", "No such owner in this college");
        }
        db.exec("""
                UPDATE listing_tenant_visibility SET status = 'SUSPENDED', status_reason = :r
                WHERE tenant_id = app_current_tenant() AND owner_user_id = :u AND status IN ('APPROVED', 'PENDING')""",
                p("u", ownerUserId, "r", TextSanitizer.truncate(TextSanitizer.cleanSingleLine(reason == null ? "Owner suspended by the college" : reason), 300)));
        audit.record("OWNER_SUSPENDED", "user", ownerUserId, Map.of("reason", reason == null ? "" : reason));
    }
}
