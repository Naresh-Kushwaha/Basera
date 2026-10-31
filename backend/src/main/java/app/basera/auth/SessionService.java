package app.basera.auth;

import app.basera.auth.dto.SessionUser;
import app.basera.auth.dto.TokenResponse;
import app.basera.common.error.ApiException;
import app.basera.config.BaseraProperties;
import app.basera.platform.PlatformJdbc;
import app.basera.security.JwtService;
import app.basera.security.Role;
import app.basera.tenancy.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions = short-lived access JWT + rotating opaque refresh token (stored hashed, grouped in a "family").
 * Reuse of an already-rotated refresh token revokes the whole family (theft detection).
 *
 * noRollbackFor = ApiException.class is essential here: when we detect reuse we revoke the family and THEN
 * throw 401; without it the revocation would be rolled back together with the exception.
 */
@Service
public class SessionService {

    public record SessionResult(TokenResponse body, String refreshToken, Duration refreshTtl) {}

    private record Principal(Role role, UUID tenantId, UUID membershipId, String displayName) {}

    private record RefreshRow(UUID id, UUID userId, UUID tenantId, UUID familyId, boolean live, boolean revoked) {}

    private final PlatformJdbc db;
    private final JwtService jwt;
    private final BaseraProperties props;

    public SessionService(PlatformJdbc db, JwtService jwt, BaseraProperties props) {
        this.db = db;
        this.jwt = jwt;
        this.props = props;
    }

    /** Single-use: the UPDATE ... RETURNING consumes the code atomically, so two racing requests cannot both win. */
    @Transactional(transactionManager = "platformTx", noRollbackFor = ApiException.class)
    public SessionResult exchange(String code, TenantContext ctx) {
        record Consumed(UUID userId, UUID tenantId) {}
        Optional<Consumed> consumed = db.client().sql("""
                UPDATE auth_code SET consumed_at = now()
                WHERE code_hash = :h AND consumed_at IS NULL AND expires_at > now()
                RETURNING user_id, tenant_id
                """)
                .param("h", TokenHashing.sha256(code))
                .query((rs, i) -> new Consumed(rs.getObject("user_id", UUID.class), rs.getObject("tenant_id", UUID.class)))
                .optional();
        Consumed c = consumed.orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "The login code is invalid or expired"));

        boolean sameScope = ctx.isTenant() ? ctx.tenantId().equals(c.tenantId())
                : (ctx.kind() == TenantContext.Kind.PLATFORM && c.tenantId() == null);
        if (!sameScope) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "The login code is invalid or expired");
        }
        return newSession(c.userId(), ctx, UUID.randomUUID());
    }

    /** Used by the dev-only login. */
    @Transactional(transactionManager = "platformTx", noRollbackFor = ApiException.class)
    public SessionResult startSession(UUID userId, TenantContext ctx) {
        return newSession(userId, ctx, UUID.randomUUID());
    }

    @Transactional(transactionManager = "platformTx", noRollbackFor = ApiException.class)
    public SessionResult refresh(String rawRefreshToken, TenantContext ctx) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw invalidRefresh();
        }
        RefreshRow row = db.client().sql("""
                SELECT id, user_id, tenant_id, family_id,
                       (expires_at > now()) AS live, (revoked_at IS NOT NULL) AS revoked
                FROM refresh_token WHERE token_hash = :h
                """)
                .param("h", TokenHashing.sha256(rawRefreshToken))
                .query((rs, i) -> new RefreshRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getBoolean("live"), rs.getBoolean("revoked")))
                .optional().orElseThrow(SessionService::invalidRefresh);

        if (row.revoked()) {                       // an already-rotated token came back: assume theft
            revokeFamily(row.familyId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_REUSED", "Session no longer valid, sign in again");
        }
        boolean sameScope = ctx.isTenant() ? ctx.tenantId().equals(row.tenantId())
                : (ctx.kind() == TenantContext.Kind.PLATFORM && row.tenantId() == null);
        if (!row.live() || !sameScope) {
            throw invalidRefresh();
        }
        int rotated = db.client().sql("UPDATE refresh_token SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", row.id()).update();
        if (rotated != 1) {                        // lost a race with another refresh using the same token
            revokeFamily(row.familyId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_REUSED", "Session no longer valid, sign in again");
        }
        try {
            SessionResult next = newSession(row.userId(), ctx, row.familyId());
            return next;
        } catch (ApiException e) {                 // membership suspended/removed since the last refresh
            revokeFamily(row.familyId());
            throw e;
        }
    }

    @Transactional(transactionManager = "platformTx")
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        db.client().sql("""
                UPDATE refresh_token SET revoked_at = now()
                WHERE revoked_at IS NULL AND family_id =
                      (SELECT family_id FROM refresh_token WHERE token_hash = :h)
                """)
                .param("h", TokenHashing.sha256(rawRefreshToken)).update();
    }

    // ---------------------------------------------------------------------------------------------------

    private SessionResult newSession(UUID userId, TenantContext ctx, UUID familyId) {
        Principal principal = principalFor(userId, ctx);

        String rawRefresh = TokenHashing.newToken();
        Duration refreshTtl = props.jwt().refreshTtl();
        db.client().sql("""
                INSERT INTO refresh_token (user_id, tenant_id, family_id, token_hash, expires_at)
                VALUES (:u, :t, :f, :h, now() + make_interval(secs => :ttl))
                """)
                .param("u", userId).param("t", principal.tenantId()).param("f", familyId)
                .param("h", TokenHashing.sha256(rawRefresh)).param("ttl", refreshTtl.toSeconds())
                .update();

        record UserRow(String fullName, String email, String photoUrl) {}
        UserRow user = db.client().sql("SELECT full_name, email, photo_url FROM user_account WHERE id = :u")
                .param("u", userId)
                .query((rs, i) -> new UserRow(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single();

        String access = jwt.issueAccessToken(userId, principal.role(), principal.tenantId(), principal.membershipId());
        String name = principal.displayName() != null ? principal.displayName() : user.fullName();
        TokenResponse body = new TokenResponse(access, "Bearer", jwt.accessTtl().toSeconds(),
                new SessionUser(userId, name, user.email(), user.photoUrl(), principal.role()));
        return new SessionResult(body, rawRefresh, refreshTtl);
    }

    private Principal principalFor(UUID userId, TenantContext ctx) {
        if (ctx.kind() == TenantContext.Kind.PLATFORM) {
            long n = db.client().sql("SELECT count(*) FROM platform_admin WHERE user_id = :u")
                    .param("u", userId).query(Long.class).single();
            if (n == 0) {
                throw ApiException.forbidden("NOT_A_PLATFORM_ADMIN", "Not a platform administrator");
            }
            return new Principal(Role.SUPER_ADMIN, null, null, null);
        }
        if (!ctx.isTenant()) {
            throw ApiException.badRequest("TENANT_REQUIRED", "A college host is required");
        }
        record M(UUID id, String role, String status, String displayName) {}
        M m = db.client().sql("SELECT id, role, status, display_name FROM membership WHERE tenant_id = :t AND user_id = :u")
                .param("t", ctx.tenantId()).param("u", userId)
                .query((rs, i) -> new M(rs.getObject("id", UUID.class), rs.getString("role"),
                        rs.getString("status"), rs.getString("display_name")))
                .optional()
                .orElseThrow(() -> ApiException.forbidden("NO_MEMBERSHIP", "You are not a member of this college"));
        if (!"ACTIVE".equals(m.status())) {
            throw ApiException.forbidden("ACCOUNT_SUSPENDED", "This account is suspended");
        }
        return new Principal(Role.valueOf(m.role()), ctx.tenantId(), m.id(), m.displayName());
    }

    private void revokeFamily(UUID familyId) {
        db.client().sql("UPDATE refresh_token SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                .param("f", familyId).update();
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh token is invalid or expired");
    }
}
