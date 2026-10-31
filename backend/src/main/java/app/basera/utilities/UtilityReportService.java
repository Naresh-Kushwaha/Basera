package app.basera.utilities;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.notification.NotificationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Student-reported outages / water quality issues, optionally tied to a listing. Owners and admins can update status. */
@Service
public class UtilityReportService {

    public record CreateRequest(UUID listingId, String kind, String description) {}

    private final Db db;
    private final Modules modules;
    private final NotificationService notifications;

    public UtilityReportService(Db db, Modules modules, NotificationService notifications) {
        this.db = db;
        this.modules = modules;
        this.notifications = notifications;
    }

    @Transactional
    public UUID create(CreateRequest r) {
        modules.require("UTILITIES");
        if (r.kind() == null || !List.of("POWER_OUTAGE", "WATER_QUALITY", "WATER_SUPPLY", "WIFI_OUTAGE", "OTHER").contains(r.kind())) {
            throw ApiException.badRequest("INVALID_KIND", "Unknown report kind");
        }
        if (r.description() == null || r.description().isBlank() || r.description().length() > 1000) {
            throw ApiException.badRequest("INVALID_DESCRIPTION", "Describe the problem in up to 1000 characters");
        }
        UUID id = db.id("""
                INSERT INTO utility_report (tenant_id, listing_id, reporter_membership_id, kind, description)
                VALUES (app_current_tenant(), :l, :me, :kind, :desc) RETURNING id""",
                p("l", r.listingId(), "me", Ctx.membershipId(), "kind", r.kind(), "desc", TextSanitizer.clean(r.description())));
        if (r.listingId() != null) {
            db.one("""
                    SELECT m.id, l.title FROM listing l JOIN membership m ON m.user_id = l.owner_user_id AND m.tenant_id = app_current_tenant()
                    WHERE l.id = :l""", p("l", r.listingId()))
                    .ifPresent(o -> notifications.notifyMember((UUID) o.get("id"), "UTILITY_REPORTED",
                            "Utility issue reported at \"" + o.get("title") + "\"", r.kind().toLowerCase().replace('_', ' '),
                            Map.of("reportId", id.toString()), false));
        }
        return id;
    }

    private static final String SELECT = """
            SELECT u.id, u.kind, u.description, u.status, u.created_at AS "createdAt", u.listing_id AS "listingId", l.title AS "listingTitle"
            FROM utility_report u LEFT JOIN listing l ON l.id = u.listing_id""";

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine() {
        return db.rows(SELECT + " WHERE u.reporter_membership_id = :me ORDER BY u.created_at DESC LIMIT 100", p("me", Ctx.membershipId()));
    }

    /** What other students reported recently for a listing (no reporter identity). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forListing(UUID listingId) {
        return db.rows("""
                SELECT u.id, u.kind, u.status, u.created_at AS "createdAt" FROM utility_report u
                WHERE u.tenant_id = app_current_tenant() AND u.listing_id = :l AND u.created_at > now() - interval '60 days'
                ORDER BY u.created_at DESC LIMIT 20""", p("l", listingId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> forOwner() {
        return db.rows(SELECT + " JOIN membership m ON m.id = u.reporter_membership_id AND m.tenant_id = u.tenant_id "
                + "WHERE l.owner_user_id = :me ORDER BY u.created_at DESC LIMIT 100", p("me", Ctx.userId()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> forAdmin(String status) {
        boolean filter = status != null && !status.isBlank();
        Map<String, Object> params = new HashMap<>();
        if (filter) {
            params.put("status", status);
        }
        return db.rows("""
                SELECT u.id, u.kind, u.description, u.status, u.created_at AS "createdAt", u.listing_id AS "listingId",
                       l.title AS "listingTitle", m.display_name AS "reporterName"
                FROM utility_report u LEFT JOIN listing l ON l.id = u.listing_id
                JOIN membership m ON m.id = u.reporter_membership_id AND m.tenant_id = u.tenant_id
                WHERE true %s ORDER BY u.created_at DESC LIMIT 200""".formatted(filter ? "AND u.status = :status" : ""), params);
    }

    @Transactional
    public void setStatus(UUID id, String status, boolean asOwner) {
        if (!List.of("OPEN", "ACKNOWLEDGED", "RESOLVED").contains(status)) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown status");
        }
        Map<String, Object> params = p("s", status, "id", id);
        String sql = asOwner
                ? "UPDATE utility_report u SET status = :s FROM listing l WHERE u.id = :id AND l.id = u.listing_id AND l.owner_user_id = :me RETURNING u.reporter_membership_id AS reporter"
                : "UPDATE utility_report SET status = :s WHERE id = :id RETURNING reporter_membership_id AS reporter";
        if (asOwner) {
            params.put("me", Ctx.userId());
        }
        Map<String, Object> row = db.require(sql, params, "REPORT_NOT_FOUND", "Report not found");
        notifications.notifyMember((UUID) row.get("reporter"), "UTILITY_" + status, "Your utility report is now " + status.toLowerCase(),
                null, Map.of("reportId", id.toString()), false);
    }
}
