package app.basera.common.audit;

import app.basera.security.CurrentUser;
import app.basera.tenancy.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to audit_log (basera_app has INSERT + SELECT only on it). MANDATORY transaction: the audit row
 * commits or rolls back together with the change it describes, and runs under the same tenant context.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String targetType, UUID targetId, Map<String, Object> details) {
        UUID tenantId = TenantContextHolder.requireTenant().tenantId();
        UUID actor = CurrentUser.get().map(u -> u.userId()).orElse(null);
        String json;
        try {
            json = objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.sql("""
                INSERT INTO audit_log (tenant_id, actor_user_id, action, target_type, target_id, details)
                VALUES (:tenant, :actor, :action, :targetType, :targetId, CAST(:details AS jsonb))
                """)
                .param("tenant", tenantId)
                .param("actor", actor)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("details", json)
                .update();
    }
}
