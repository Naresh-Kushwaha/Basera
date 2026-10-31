package app.basera.identity;

import app.basera.common.audit.BaseEntity;
import app.basera.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation")
public class Invitation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected Invitation() {}

    public Invitation(UUID tenantId, String email, Role role, Instant expiresAt) {
        this.tenantId = tenantId;
        this.email = email;
        this.role = role;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public String getEmail() { return email; }

    public Role getRole() { return role; }

    public Instant getExpiresAt() { return expiresAt; }

    public Instant getConsumedAt() { return consumedAt; }
}
