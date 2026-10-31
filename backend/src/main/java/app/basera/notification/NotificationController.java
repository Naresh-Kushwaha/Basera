package app.basera.notification;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private final Db db;

    public NotificationController(Db db) {
        this.db = db;
    }

    public record Prefs(boolean emailNotifications) {}

    @GetMapping("/notifications")
    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(defaultValue = "false") boolean unreadOnly) {
        Pageable pg = Paging.of(page, size);
        String where = "recipient_membership_id = :m" + (unreadOnly ? " AND read_at IS NULL" : "");
        long total = db.count("SELECT count(*) FROM notification WHERE " + where, p("m", Ctx.membershipId()));
        List<Map<String, Object>> items = db.rows("""
                SELECT id, type, title, body, data, read_at AS "readAt", created_at AS "createdAt"
                FROM notification WHERE %s ORDER BY created_at DESC LIMIT :limit OFFSET :offset""".formatted(where),
                p("m", Ctx.membershipId(), "limit", pg.getPageSize(), "offset", (int) pg.getOffset()));
        return PageResponse.of(items, pg.getPageNumber(), pg.getPageSize(), total);
    }

    @GetMapping("/notifications/unread-count")
    @Transactional(readOnly = true)
    public Map<String, Object> unread() {
        return Map.of("count", db.count(
                "SELECT count(*) FROM notification WHERE recipient_membership_id = :m AND read_at IS NULL",
                p("m", Ctx.membershipId())));
    }

    @PostMapping("/notifications/{id}/read")
    @Transactional
    public ResponseEntity<Void> read(@PathVariable UUID id) {
        db.exec("UPDATE notification SET read_at = now() WHERE id = :id AND recipient_membership_id = :m AND read_at IS NULL",
                p("id", id, "m", Ctx.membershipId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    @Transactional
    public ResponseEntity<Void> readAll() {
        db.exec("UPDATE notification SET read_at = now() WHERE recipient_membership_id = :m AND read_at IS NULL",
                p("m", Ctx.membershipId()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/notification-preferences")
    @Transactional(readOnly = true)
    public Prefs getPrefs() {
        return new Prefs(db.count("SELECT count(*) FROM membership WHERE id = :m AND email_notifications",
                p("m", Ctx.membershipId())) > 0);
    }

    @PutMapping("/me/notification-preferences")
    @Transactional
    public Prefs setPrefs(@RequestBody Prefs prefs) {
        db.exec("UPDATE membership SET email_notifications = :e WHERE id = :m",
                p("e", prefs.emailNotifications(), "m", Ctx.membershipId()));
        return prefs;
    }
}
