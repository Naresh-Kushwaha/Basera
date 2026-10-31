package app.basera.tenancy;

import app.basera.common.error.ErrorResponseWriter;
import app.basera.security.AuthenticatedUser;
import app.basera.security.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs right AFTER bearer-token authentication. A token is bound to exactly one tenant (claim "tid"):
 * presenting a tenant-A token on tenant-B's host is rejected with 403 before any controller runs.
 * SUPER_ADMIN tokens are bound to the platform host. Instantiated by SecurityConfig (not a bean).
 */
public class TenantBindingFilter extends OncePerRequestFilter {

    private final ErrorResponseWriter writer;

    public TenantBindingFilter(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            AuthenticatedUser user = AuthenticatedUser.from(jwtAuth.getToken());
            TenantContext ctx = TenantContextHolder.get();
            boolean ok;
            if (user.role() == Role.SUPER_ADMIN) {
                ok = ctx.kind() == TenantContext.Kind.PLATFORM;
            } else {
                ok = ctx.isTenant() && user.tenantId() != null && user.tenantId().equals(ctx.tenantId());
            }
            if (!ok) {
                writer.write(request, response, HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                        "This session does not belong to this college");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
