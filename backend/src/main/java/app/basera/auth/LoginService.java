package app.basera.auth;

import app.basera.common.validation.TextSanitizer;
import app.basera.config.BaseraProperties;
import app.basera.platform.PlatformJdbc;
import app.basera.security.Role;
import app.basera.tenancy.TenantContext;
import app.basera.tenancy.TenantInfo;
import app.basera.tenancy.TenantLookupService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a verified Google identity into (user_account, membership) and a one-time auth code.
 * Runs on the platform pool because no tenant context exists yet at login time.
 */
@Service
public class LoginService {

    private record MembershipRow(UUID id, String role, String status, boolean collegeVerified) {}

    private record InvitationRow(UUID id, String role) {}

    private final PlatformJdbc db;
    private final TenantLookupService tenants;
    private final BaseraProperties props;

    public LoginService(PlatformJdbc db, TenantLookupService tenants, BaseraProperties props) {
        this.db = db;
        this.tenants = tenants;
        this.props = props;
    }

    /** @param tenantSlug null means a SUPER_ADMIN (platform) login. @return the raw one-time code. */
    @Transactional(transactionManager = "platformTx")
    public String completeLogin(GoogleProfile profile, String tenantSlug) {
        if (!profile.emailVerified()) {
            throw new LoginDeniedException("email_not_verified");
        }
        UUID userId = upsertUser(profile);

        UUID tenantId = null;
        if (tenantSlug == null) {
            ensurePlatformAdmin(userId, profile.email());
        } else {
            TenantInfo tenant = tenants.findBySlug(tenantSlug).filter(TenantInfo::active)
                    .orElseThrow(() -> new LoginDeniedException("unknown_tenant"));
            resolveMembership(tenant, userId, profile);
            tenantId = tenant.id();
        }
        return issueCode(userId, tenantId);
    }

    /** Local development only (DevLoginController): creates the user + membership/admin row directly. */
    @Transactional(transactionManager = "platformTx")
    public UUID devProvision(String email, String name, Role role, TenantContext ctx) {
        UUID userId = upsertUser(new GoogleProfile("dev:" + email.toLowerCase(), email.toLowerCase(), true,
                name, null, null));
        if (role == Role.SUPER_ADMIN) {
            db.client().sql("INSERT INTO platform_admin (user_id) VALUES (:u) ON CONFLICT DO NOTHING")
                    .param("u", userId).update();
        } else {
            if (!ctx.isTenant()) {
                throw new LoginDeniedException("tenant_required");
            }
            db.client().sql("""
                    INSERT INTO membership (tenant_id, user_id, role, display_name)
                    VALUES (:t, :u, :role, :name)
                    ON CONFLICT (tenant_id, user_id) DO UPDATE SET role = EXCLUDED.role, status = 'ACTIVE'
                    """)
                    .param("t", ctx.tenantId()).param("u", userId)
                    .param("role", role.name()).param("name", TextSanitizer.truncate(name, 100))
                    .update();
        }
        return userId;
    }

    private UUID upsertUser(GoogleProfile p) {
        String name = TextSanitizer.truncate(TextSanitizer.cleanSingleLine(
                p.name() == null || p.name().isBlank() ? p.email() : p.name()), 100);
        return db.client().sql("""
                INSERT INTO user_account (google_sub, email, email_verified, full_name, photo_url, hosted_domain, last_login_at)
                VALUES (:sub, :email, :verified, :name, :photo, :hd, now())
                ON CONFLICT (google_sub) DO UPDATE SET
                    email = EXCLUDED.email, email_verified = EXCLUDED.email_verified,
                    full_name = EXCLUDED.full_name, photo_url = EXCLUDED.photo_url,
                    hosted_domain = EXCLUDED.hosted_domain, last_login_at = now()
                RETURNING id
                """)
                .param("sub", p.sub()).param("email", p.email()).param("verified", p.emailVerified())
                .param("name", name).param("photo", p.picture()).param("hd", p.hostedDomain())
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();
    }

    /** SUPER_ADMIN set = rows in platform_admin, bootstrapped from BASERA_SUPER_ADMIN_EMAILS (verified emails only). */
    private void ensurePlatformAdmin(UUID userId, String email) {
        boolean configured = props.platform().superAdminEmails().stream()
                .anyMatch(e -> e.trim().equalsIgnoreCase(email));
        if (configured) {
            db.client().sql("INSERT INTO platform_admin (user_id) VALUES (:u) ON CONFLICT DO NOTHING")
                    .param("u", userId).update();
        }
        boolean isAdmin = db.client().sql("SELECT count(*) FROM platform_admin WHERE user_id = :u")
                .param("u", userId).query(Long.class).single() > 0;
        if (!isAdmin) {
            throw new LoginDeniedException("not_a_platform_admin");
        }
    }

    private void resolveMembership(TenantInfo tenant, UUID userId, GoogleProfile p) {
        String displayName = TextSanitizer.truncate(TextSanitizer.cleanSingleLine(
                p.name() == null || p.name().isBlank() ? p.email() : p.name()), 100);
        boolean domainMatch = tenant.emailDomain() != null && p.email().endsWith("@" + tenant.emailDomain());

        Optional<MembershipRow> existing = db.client().sql("""
                SELECT id, role, status, (college_email_verified_at IS NOT NULL) AS verified
                FROM membership WHERE tenant_id = :t AND user_id = :u
                """)
                .param("t", tenant.id()).param("u", userId)
                .query((rs, i) -> new MembershipRow(rs.getObject("id", UUID.class), rs.getString("role"),
                        rs.getString("status"), rs.getBoolean("verified")))
                .optional();

        Optional<InvitationRow> invitation = db.client().sql("""
                SELECT id, role FROM invitation
                WHERE tenant_id = :t AND email = :email AND consumed_at IS NULL AND expires_at > now()
                ORDER BY CASE role WHEN 'TENANT_ADMIN' THEN 0 ELSE 1 END
                LIMIT 1
                """)
                .param("t", tenant.id()).param("email", p.email())
                .query((rs, i) -> new InvitationRow(rs.getObject("id", UUID.class), rs.getString("role")))
                .optional();

        if (existing.isPresent()) {
            MembershipRow m = existing.get();
            if (!"ACTIVE".equals(m.status())) {
                throw new LoginDeniedException("account_suspended");
            }
            String newRole = m.role();
            if (invitation.isPresent() && rank(invitation.get().role()) < rank(m.role())) {
                newRole = invitation.get().role();   // upgrade only, never downgrade
            }
            db.client().sql("""
                    UPDATE membership SET role = :role, display_name = :name, photo_url = :photo,
                        college_email = CASE WHEN :verify AND college_email_verified_at IS NULL THEN :email ELSE college_email END,
                        college_email_verified_at = CASE WHEN :verify AND college_email_verified_at IS NULL
                                                         THEN now() ELSE college_email_verified_at END
                    WHERE id = :id
                    """)
                    .param("role", newRole).param("name", displayName).param("photo", p.picture())
                    .param("verify", domainMatch).param("email", p.email()).param("id", m.id())
                    .update();
            invitation.ifPresent(i -> consumeInvitation(i.id(), userId));
            return;
        }

        String role;
        if (invitation.isPresent()) {
            role = invitation.get().role();
        } else if ("OPEN".equals(tenant.joinPolicy())) {
            role = Role.STUDENT.name();
        } else if (domainMatch) {
            role = Role.STUDENT.name();
        } else {
            throw new LoginDeniedException("domain_not_allowed");
        }

        db.client().sql("""
                INSERT INTO membership (tenant_id, user_id, role, display_name, photo_url, college_email, college_email_verified_at)
                VALUES (:t, :u, :role, :name, :photo, CASE WHEN :verify THEN :email END,
                        CASE WHEN :verify THEN now() END)
                """)
                .param("t", tenant.id()).param("u", userId).param("role", role)
                .param("name", displayName).param("photo", p.picture())
                .param("verify", domainMatch).param("email", p.email())
                .update();
        invitation.ifPresent(i -> consumeInvitation(i.id(), userId));
    }

    private void consumeInvitation(UUID invitationId, UUID userId) {
        db.client().sql("UPDATE invitation SET consumed_at = now(), consumed_by = :u WHERE id = :id")
                .param("u", userId).param("id", invitationId).update();
    }

    private static int rank(String role) {
        return switch (role) {
            case "TENANT_ADMIN" -> 0;
            case "OWNER" -> 1;
            default -> 2;
        };
    }

    private String issueCode(UUID userId, UUID tenantId) {
        String raw = TokenHashing.newToken();
        db.client().sql("""
                INSERT INTO auth_code (code_hash, user_id, tenant_id, expires_at)
                VALUES (:h, :u, :t, now() + make_interval(secs => :ttl))
                """)
                .param("h", TokenHashing.sha256(raw)).param("u", userId).param("t", tenantId)
                .param("ttl", props.auth().authCodeTtl().toSeconds())
                .update();
        return raw;
    }
}
