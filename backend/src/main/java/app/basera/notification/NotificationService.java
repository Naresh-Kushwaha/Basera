package app.basera.notification;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications are always created. If the recipient opted in (membership.email_notifications) and the event is
 * email-worthy, an email_outbox row referencing the MEMBERSHIP is queued; the platform-side dispatcher resolves the
 * address at send time, so no tenant code ever reads another user's email.
 */
@Service
public class NotificationService {

    private final Db db;
    private final ObjectMapper mapper;

    public NotificationService(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyMember(UUID membershipId, String type, String title, String body, Map<String, Object> data,
                             boolean email) {
        if (membershipId == null || membershipId.equals(Ctx.membershipId())) {
            return;   // never notify yourself
        }
        db.exec("""
                INSERT INTO notification (tenant_id, recipient_membership_id, type, title, body, data)
                VALUES (app_current_tenant(), :m, :type, :title, :body, CAST(:data AS jsonb))
                """, p("m", membershipId, "type", type, "title", title, "body", body, "data", json(data)));
        if (email) {
            db.exec("""
                    INSERT INTO email_outbox (tenant_id, recipient_membership_id, subject, body)
                    SELECT app_current_tenant(), m.id, :title, :body FROM membership m
                    WHERE m.id = :m AND m.email_notifications
                    """, p("m", membershipId, "title", title, "body", body == null ? title : body));
        }
    }

    /** Notifies every TENANT_ADMIN of the current college. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyAdmins(String type, String title, String body, Map<String, Object> data) {
        List<Map<String, Object>> admins = db.rows("SELECT id FROM membership WHERE role = 'TENANT_ADMIN' AND status = 'ACTIVE'", p());
        for (Map<String, Object> a : admins) {
            notifyMember((UUID) a.get("id"), type, title, body, data, true);
        }
    }

    private String json(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
