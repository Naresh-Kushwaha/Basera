package app.basera.guide;

import static app.basera.common.db.Db.p;

import app.basera.common.audit.AuditService;
import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * Local guide. City-level content (transport, clinics, emergency numbers, safe-area notes, KYC guides) is stored ONCE per
 * city and read here through the tenant's city; the college-specific parts (first-week checklist, typical costs) are
 * tenant-owned. Every city row carries lastReviewedAt so the UI shows how fresh it is.
 */
@RestController
@RequestMapping("/api/v1")
public class GuideController {

    private static final String CITY = "(SELECT city_id FROM tenant WHERE id = app_current_tenant())";

    public record ChecklistDone(boolean done) {}

    public record CostItem(String category, String label, Integer typicalMin, Integer typicalMax, String note) {}

    public record ChecklistItem(String title, String description, Integer sortOrder) {}

    private final Db db;
    private final Modules modules;
    private final AuditService audit;

    public GuideController(Db db, Modules modules, AuditService audit) {
        this.db = db;
        this.modules = modules;
        this.audit = audit;
    }

    @GetMapping("/guide/transport")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> transport() {
        modules.require("GUIDE");
        return db.rows("""
                SELECT id, mode, name, from_place AS "fromPlace", to_place AS "toPlace", fare_min AS "fareMin", fare_max AS "fareMax",
                       frequency_note AS "frequencyNote", notes, last_reviewed_at AS "lastReviewedAt"
                FROM transport_route WHERE city_id = %s ORDER BY mode, name""".formatted(CITY), p());
    }

    @GetMapping("/guide/health")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> health(@RequestParam(required = false) String kind) {
        modules.require("GUIDE");
        boolean filter = kind != null && !kind.isBlank();
        return db.rows("""
                SELECT h.id, h.kind, h.name, h.address, h.phone, h.lat, h.lng, h.open_24x7 AS "open24x7",
                       h.last_reviewed_at AS "lastReviewedAt",
                       CASE WHEN h.geog IS NULL THEN NULL ELSE round(ST_Distance(h.geog, t.campus_geog))::int END AS "distanceM"
                FROM health_facility h JOIN tenant t ON t.id = app_current_tenant()
                WHERE h.city_id = t.city_id %s ORDER BY "distanceM" NULLS LAST, h.name""".formatted(filter ? "AND h.kind = :kind" : ""),
                filter ? p("kind", kind) : p());
    }

    /** Safety-critical: always available, even if the GUIDE module is switched off for the college. */
    @GetMapping("/guide/emergency")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> emergency() {
        return db.rows("""
                SELECT id, label, phone, description, last_reviewed_at AS "lastReviewedAt"
                FROM emergency_contact WHERE city_id = %s ORDER BY sort_order, label""".formatted(CITY), p());
    }

    @GetMapping("/guide/safe-areas")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> safeAreas() {
        modules.require("GUIDE");
        return db.rows("""
                SELECT id, area_name AS "areaName", note, source, last_reviewed_at AS "lastReviewedAt"
                FROM safe_area_note WHERE city_id = %s ORDER BY area_name""".formatted(CITY), p());
    }

    @GetMapping("/guide/kyc")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> kyc() {
        modules.require("GUIDE");
        return db.rows("""
                SELECT id, title, body_md AS "bodyMd", last_reviewed_at AS "lastReviewedAt"
                FROM bank_kyc_guide WHERE city_id = %s ORDER BY title""".formatted(CITY), p());
    }

    @GetMapping("/guide/first-week-checklist")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> checklist() {
        modules.require("GUIDE");
        return db.rows("""
                SELECT ci.id, ci.title, ci.description, (cp.completed_at IS NOT NULL) AS done
                FROM checklist_item ci LEFT JOIN checklist_progress cp ON cp.item_id = ci.id AND cp.membership_id = :me
                ORDER BY ci.sort_order, ci.created_at""", p("me", Ctx.membershipId()));
    }

    @PutMapping("/me/checklist/{itemId}")
    @Transactional
    public ResponseEntity<Void> tick(@PathVariable UUID itemId, @RequestBody ChecklistDone body) {
        if (body.done()) {
            db.exec("""
                    INSERT INTO checklist_progress (membership_id, item_id, tenant_id) VALUES (:me, :i, app_current_tenant())
                    ON CONFLICT (membership_id, item_id) DO NOTHING""", p("me", Ctx.membershipId(), "i", itemId));
        } else {
            db.exec("DELETE FROM checklist_progress WHERE membership_id = :me AND item_id = :i", p("me", Ctx.membershipId(), "i", itemId));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/budget/typical-costs")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> typicalCosts() {
        modules.require("BUDGET");
        return costItems();
    }

    // ---- TENANT_ADMIN: cost guide + checklist --------------------------------------------------------------------

    @GetMapping("/admin/guide/cost-items")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> costItems() {
        return db.rows("""
                SELECT id, category, label, typical_min AS "typicalMin", typical_max AS "typicalMax", note
                FROM cost_guide_item ORDER BY category, label""", p());
    }

    @PostMapping("/admin/guide/cost-items")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> addCost(@RequestBody CostItem c) {
        validateCost(c);
        UUID id = db.id("""
                INSERT INTO cost_guide_item (tenant_id, category, label, typical_min, typical_max, note)
                VALUES (app_current_tenant(), :cat, :label, :min, :max, :note) RETURNING id""", costParams(c));
        audit.record("COST_ITEM_CREATED", "cost_guide_item", id, Map.of());
        return Map.of("id", id);
    }

    @PutMapping("/admin/guide/cost-items/{id}")
    @Transactional
    public ResponseEntity<Void> updateCost(@PathVariable UUID id, @RequestBody CostItem c) {
        validateCost(c);
        Map<String, Object> params = costParams(c);
        params.put("id", id);
        db.execOrNotFound("UPDATE cost_guide_item SET category = :cat, label = :label, typical_min = :min, typical_max = :max, note = :note WHERE id = :id",
                params, "ITEM_NOT_FOUND", "Item not found");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/guide/cost-items/{id}")
    @Transactional
    public ResponseEntity<Void> deleteCost(@PathVariable UUID id) {
        db.execOrNotFound("DELETE FROM cost_guide_item WHERE id = :id", p("id", id), "ITEM_NOT_FOUND", "Item not found");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/guide/checklist-items")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> checklistItems() {
        return db.rows("""
                SELECT id, title, description, sort_order AS "sortOrder" FROM checklist_item ORDER BY sort_order, created_at""", p());
    }

    @PostMapping("/admin/guide/checklist-items")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> addChecklist(@RequestBody ChecklistItem c) {
        validateChecklist(c);
        UUID id = db.id("""
                INSERT INTO checklist_item (tenant_id, title, description, sort_order)
                VALUES (app_current_tenant(), :title, :desc, COALESCE(:sort, 0)) RETURNING id""", checklistParams(c));
        audit.record("CHECKLIST_ITEM_CREATED", "checklist_item", id, Map.of());
        return Map.of("id", id);
    }

    @PutMapping("/admin/guide/checklist-items/{id}")
    @Transactional
    public ResponseEntity<Void> updateChecklist(@PathVariable UUID id, @RequestBody ChecklistItem c) {
        validateChecklist(c);
        Map<String, Object> params = checklistParams(c);
        params.put("id", id);
        db.execOrNotFound("UPDATE checklist_item SET title = :title, description = :desc, sort_order = COALESCE(:sort, 0) WHERE id = :id",
                params, "ITEM_NOT_FOUND", "Item not found");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/guide/checklist-items/{id}")
    @Transactional
    public ResponseEntity<Void> deleteChecklist(@PathVariable UUID id) {
        db.execOrNotFound("DELETE FROM checklist_item WHERE id = :id", p("id", id), "ITEM_NOT_FOUND", "Item not found");
        return ResponseEntity.noContent().build();
    }

    private static void validateCost(CostItem c) {
        if (c.category() == null || c.category().isBlank() || c.label() == null || c.label().isBlank()
                || c.category().length() > 60 || c.label().length() > 120) {
            throw ApiException.badRequest("INVALID_ITEM", "category and label are required");
        }
        if (c.typicalMin() != null && c.typicalMax() != null && c.typicalMax() < c.typicalMin()) {
            throw ApiException.badRequest("INVALID_RANGE", "typicalMax must be at least typicalMin");
        }
    }

    private static Map<String, Object> costParams(CostItem c) {
        return p("cat", TextSanitizer.cleanSingleLine(c.category()), "label", TextSanitizer.cleanSingleLine(c.label()),
                "min", c.typicalMin(), "max", c.typicalMax(), "note", TextSanitizer.truncate(TextSanitizer.clean(c.note()), 500));
    }

    private static void validateChecklist(ChecklistItem c) {
        if (c.title() == null || c.title().isBlank() || c.title().length() > 150) {
            throw ApiException.badRequest("INVALID_ITEM", "title is required (max 150 characters)");
        }
    }

    private static Map<String, Object> checklistParams(ChecklistItem c) {
        return p("title", TextSanitizer.cleanSingleLine(c.title()), "desc", TextSanitizer.truncate(TextSanitizer.clean(c.description()), 500),
                "sort", c.sortOrder());
    }
}
