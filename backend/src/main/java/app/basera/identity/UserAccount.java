package app.basera.identity;

import app.basera.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Global identity (Google). Read-only for the application role: row visibility is limited by RLS to the user
 * themself, plus (for TENANT_ADMIN) accounts that have a membership in the admin's tenant.
 */
@Entity
@Table(name = "user_account")
public class UserAccount extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "google_sub", nullable = false, updatable = false)
    private String googleSub;

    @Column(nullable = false)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "hosted_domain")
    private String hostedDomain;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserAccount() {}

    public UUID getId() { return id; }

    public String getGoogleSub() { return googleSub; }

    public String getEmail() { return email; }

    public boolean isEmailVerified() { return emailVerified; }

    public String getFullName() { return fullName; }

    public String getPhotoUrl() { return photoUrl; }

    public String getHostedDomain() { return hostedDomain; }

    public Instant getLastLoginAt() { return lastLoginAt; }
}
