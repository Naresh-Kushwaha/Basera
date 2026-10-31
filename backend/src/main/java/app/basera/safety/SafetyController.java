package app.basera.safety;

import static app.basera.common.db.Db.p;

import app.basera.common.audit.AuditService;
import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.notification.NotificationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is REALISTIC here: trusted-contact list, a one-tap screen that shows the city's emergency numbers and builds a
 * ready-to-send help message (the phone's own share sheet / SMS / call does the sending), and a report-an-issue flow to the
 * college admin. NOT provided (needs external partners): dispatching police/ambulance, 24x7 monitoring, guaranteed SMS delivery,
 * background live-location tracking. The app never claims otherwise.
 */
@RestController
@RequestMapping("/api/v1")
public class SafetyController {

    private static final Pattern PHONE = Pattern.compile("^[0-9+\\- ]{7,20}$");
    private static final int MAX_CONTACTS = 5;

    public record ContactRequest(String name, String phone, String relationship) {}

    public record SosRequest(Double lat, Double lng, boolean alertCollegeAdmin) {}

    public record IssueRequest(String category, String description, String locationText) {}

    public record IssueStatus(String status, String resolutionNote) {}

    private final Db db;
    private final Modules modules;
    private final NotificationService notifications;
    private final AuditService audit;

    public SafetyController(Db db, Modules modules, NotificationService notifications, AuditService audit) {
        this.db = db;
        this.modules = modules;
        this.notifications = notifications;
        this.audit = audit;
    }

    @GetMapping("/safety/contacts")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> contacts() {
        return db.rows("SELECT id, name, phone, relationship FROM sos_contact WHERE membership_id = :me ORDER BY created_at", p("me", Ctx.membershipId()));
    }

    @PostMapping("/safety/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> addContact(@RequestBody ContactRequest r) {
        modules.require("SAFETY");
        validate(r);
        if (db.count("SELECT count(*) FROM sos_contact WHERE membership_id = :me", p("me", Ctx.membershipId())) >= MAX_CONTACTS) {
            throw ApiException.conflict("TOO_MANY_CONTACTS", "You can save up to " + MAX_CONTACTS + " trusted contacts");
        }
        return Map.of("id", db.id("""
                INSERT INTO sos_contact (tenant_id, membership_id, name, phone, relationship)
                VALUES (app_current_tenant(), :me, :name, :phone, :rel) RETURNING id""", contactParams(r)));
    }

    @PutMapping("/safety/contacts/{id}")
    @Transactional
    public ResponseEntity<Void> updateContact(@PathVariable UUID id, @RequestBody ContactRequest r) {
        validate(r);
        Map<String, Object> params = contactParams(r);
        params.put("id", id);
        db.execOrNotFound("UPDATE sos_contact SET name = :name, phone = :phone, relationship = :rel WHERE id = :id AND membership_id = :me",
                params, "CONTACT_NOT_FOUND", "Contact not found");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/safety/contacts/{id}")
    @Transactional
    public ResponseEntity<Void> deleteContact(@PathVariable UUID id) {
        db.execOrNotFound("DELETE FROM sos_contact WHERE id = :id AND membership_id = :me", p("id", id, "me", Ctx.membershipId()),
                "CONTACT_NOT_FOUND", "Contact not found");
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns what the client needs to ask for help: emergency numbers, the member's trusted contacts and a prepared message.
     * Nothing is sent from the server. Only if alertCollegeAdmin is true is the college admin notified and an OPEN safety issue logged.
     */
    @PostMapping("/safety/sos")
    @Transactional
    public Map<String, Object> sos(@RequestBody SosRequest r) {
        modules.require("SAFETY");
        boolean hasLocation = r.lat() != null && r.lng() != null && Math.abs(r.lat()) <= 90 && Math.abs(r.lng()) <= 180;
        String link = hasLocation ? "https://www.openstreetmap.org/?mlat=" + r.lat() + "&mlon=" + r.lng() + "#map=18/" + r.lat() + "/" + r.lng() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emergencyNumbers", db.rows("""
                SELECT label, phone, description FROM emergency_contact
                WHERE city_id = (SELECT city_id FROM tenant WHERE id = app_current_tenant()) ORDER BY sort_order, label""", p()));
        out.put("contacts", contacts());
        out.put("message", "I need help." + (link == null ? "" : " My location: " + link));
        out.put("locationLink", link);
        if (r.alertCollegeAdmin()) {
            UUID issue = db.id("""
                    INSERT INTO issue_report (tenant_id, reporter_membership_id, category, description, location_text)
                    VALUES (app_current_tenant(), :me, 'SAFETY', 'SOS alert raised by a student', :loc) RETURNING id""",
                    p("me", Ctx.membershipId(), "loc", hasLocation ? r.lat() + "," + r.lng() : null));
            notifications.notifyAdmins("SOS_ALERT", "SOS alert from a student", "Open the safety issues list now.",
                    Map.of("issueId", issue.toString()));
            out.put("collegeAlerted", true);
        } else {
            out.put("collegeAlerted", false);
        }
        return out;
    }

    @PostMapping("/safety/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> report(@RequestBody IssueRequest r) {
        modules.require("SAFETY");
        if (r.category() == null || !List.of("SAFETY", "HARASSMENT", "THEFT", "HEALTH", "INFRASTRUCTURE", "OTHER").contains(r.category())) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category");
        }
        if (r.description() == null || r.description().isBlank() || r.description().length() > 3000) {
            throw ApiException.badRequest("INVALID_DESCRIPTION", "Describe the issue in up to 3000 characters");
        }
        UUID id = db.id("""
                INSERT INTO issue_report (tenant_id, reporter_membership_id, category, description, location_text)
                VALUES (app_current_tenant(), :me, :cat, :desc, :loc) RETURNING id""",
                p("me", Ctx.membershipId(), "cat", r.category(), "desc", TextSanitizer.clean(r.description()),
                        "loc", TextSanitizer.truncate(TextSanitizer.cleanSingleLine(r.locationText()), 200)));
        notifications.notifyAdmins("ISSUE_REPORTED", "New " + r.category().toLowerCase() + " report", null, Map.of("issueId", id.toString()));
        return Map.of("id", id);
    }

    @GetMapping("/safety/issues")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myIssues() {
        return db.rows("""
                SELECT id, category, description, location_text AS "locationText", status, resolution_note AS "resolutionNote",
                       created_at AS "createdAt" FROM issue_report WHERE reporter_membership_id = :me ORDER BY created_at DESC""",
                p("me", Ctx.membershipId()));
    }

    // ---- TENANT_ADMIN -------------------------------------------------------------------------------------------

    @GetMapping("/admin/safety/issues")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> adminIssues(@RequestParam(required = false) String status) {
        boolean filter = status != null && !status.isBlank();
        return db.rows("""
                SELECT i.id, i.category, i.description, i.location_text AS "locationText", i.status, i.resolution_note AS "resolutionNote",
                       i.created_at AS "createdAt", m.display_name AS "reporterName"
                FROM issue_report i JOIN membership m ON m.id = i.reporter_membership_id AND m.tenant_id = i.tenant_id
                WHERE true %s ORDER BY CASE i.status WHEN 'OPEN' THEN 0 WHEN 'IN_REVIEW' THEN 1 ELSE 2 END, i.created_at DESC LIMIT 200"""
                .formatted(filter ? "AND i.status = :status" : ""), filter ? p("status", status) : p());
    }

    @PatchMapping("/admin/safety/issues/{id}")
    @Transactional
    public ResponseEntity<Void> updateIssue(@PathVariable UUID id, @RequestBody IssueStatus r) {
        if (r.status() == null || !List.of("OPEN", "IN_REVIEW", "RESOLVED").contains(r.status())) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown status");
        }
        Map<String, Object> row = db.require("""
                UPDATE issue_report SET status = :s, resolution_note = :note, assigned_to = :me WHERE id = :id
                RETURNING reporter_membership_id AS reporter""",
                p("s", r.status(), "note", TextSanitizer.truncate(TextSanitizer.clean(r.resolutionNote()), 2000), "me", Ctx.userId(), "id", id),
                "ISSUE_NOT_FOUND", "Issue not found");
        audit.record("ISSUE_" + r.status(), "issue_report", id, Map.of());
        notifications.notifyMember((UUID) row.get("reporter"), "ISSUE_UPDATED", "Your report is now " + r.status().toLowerCase().replace('_', ' '),
                r.resolutionNote(), Map.of("issueId", id.toString()), true);
        return ResponseEntity.noContent().build();
    }

    private static void validate(ContactRequest r) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 80) {
            throw ApiException.badRequest("INVALID_NAME", "A name up to 80 characters is required");
        }
        if (r.phone() == null || !PHONE.matcher(r.phone()).matches()) {
            throw ApiException.badRequest("INVALID_PHONE", "Phone may contain digits, +, - and spaces (7-20 characters)");
        }
    }

    private static Map<String, Object> contactParams(ContactRequest r) {
        return p("me", Ctx.membershipId(), "name", TextSanitizer.cleanSingleLine(r.name()), "phone", r.phone(),
                "rel", TextSanitizer.truncate(TextSanitizer.cleanSingleLine(r.relationship()), 40));
    }
}
