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

/** A user's account inside ONE college: role, status and the tenant-visible profile. Created at login (platform pool). */
@Entity
@Table(name = "membership")
public class Membership extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipStatus status;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "college_email")
    private String collegeEmail;

    @Column(name = "college_email_verified_at")
    private Instant collegeEmailVerifiedAt;

    protected Membership() {}

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public UUID getUserId() { return userId; }

    public Role getRole() { return role; }

    public void setRole(Role role) { this.role = role; }

    public MembershipStatus getStatus() { return status; }

    public void setStatus(MembershipStatus status) { this.status = status; }

    public String getDisplayName() { return displayName; }

    public String getPhotoUrl() { return photoUrl; }

    public String getCollegeEmail() { return collegeEmail; }

    public Instant getCollegeEmailVerifiedAt() { return collegeEmailVerifiedAt; }
}
