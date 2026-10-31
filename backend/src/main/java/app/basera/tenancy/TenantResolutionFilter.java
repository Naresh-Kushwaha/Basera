package app.basera.tenancy;

import app.basera.common.error.ErrorResponseWriter;
import app.basera.config.BaseraProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the tenant for every request and stores it in TenantContextHolder.
 *
 * Production: the subdomain of the Host header is authoritative (<slug>.<base-domain>).
 * Dev/tests only (basera.tenant.header-enabled=true): the X-Tenant-ID header may carry a tenant UUID or
 * slug, or the literals "platform" / "auth".
 *
 * Hosts also decide which endpoints exist at all:
 *   auth.<base>   -> only /api/v1/auth/login and /api/v1/auth/google/callback
 *   admin.<base>  -> /api/v1/platform/**, /api/v1/auth/{token,refresh,logout}
 *   <slug>.<base> -> everything except /platform and the Google login endpoints
 *
 * Instantiated by SecurityConfig (deliberately NOT a bean, so it is not registered twice).
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String CALLBACK = "/api/v1/auth/google/callback";

    private final TenantLookupService tenants;
    private final BaseraProperties.Tenant props;
    private final ErrorResponseWriter writer;

    public TenantResolutionFilter(TenantLookupService tenants, BaseraProperties.Tenant props,
                                  ErrorResponseWriter writer) {
        this.tenants = tenants;
        this.props = props;
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.startsWith("/actuator") || p.startsWith("/v3/api-docs") || p.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContext ctx;
        try {
            ctx = resolve(request);
        } catch (UnknownTenantException e) {
            writer.write(request, response, HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Unknown college");
            return;
        }

        if (ctx.kind() == TenantContext.Kind.NONE) {
            writer.write(request, response, HttpStatus.BAD_REQUEST, "TENANT_REQUIRED",
                    "Call this API on a college host (<slug>." + props.baseDomain() + ")");
            return;
        }
        if (!pathAllowed(ctx, request.getRequestURI())) {
            writer.write(request, response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
            return;
        }

        TenantContextHolder.set(ctx);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    TenantContext resolve(HttpServletRequest request) {
        String header = props.headerEnabled() ? request.getHeader("X-Tenant-ID") : null;
        if (header != null && !header.isBlank()) {
            return fromToken(header.trim().toLowerCase(Locale.ROOT));
        }
        String host = hostOf(request);
        String suffix = "." + props.baseDomain().toLowerCase(Locale.ROOT);
        if (host != null && host.endsWith(suffix)) {
            String label = host.substring(0, host.length() - suffix.length());
            if (!label.isEmpty() && !label.contains(".")) {
                return fromToken(label);
            }
        }
        return TenantContext.NONE;
    }

    private TenantContext fromToken(String token) {
        switch (token) {
            case "auth":
                return TenantContext.AUTH;
            case "admin":
            case "platform":
                return TenantContext.PLATFORM;
            default:
                break;
        }
        Optional<TenantInfo> found = isUuid(token)
                ? tenants.findById(UUID.fromString(token))
                : tenants.findBySlug(token);
        TenantInfo info = found.filter(TenantInfo::active).orElseThrow(UnknownTenantException::new);
        return TenantContext.tenant(info.id(), info.slug());
    }

    private static boolean pathAllowed(TenantContext ctx, String path) {
        boolean google = path.equals(LOGIN) || path.equals(CALLBACK);
        return switch (ctx.kind()) {
            case AUTH -> google;
            case PLATFORM -> !google && (path.startsWith("/api/v1/platform/")
                    || path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/dev/"));
            case TENANT -> !google && !path.startsWith("/api/v1/platform/");
            case NONE -> false;
        };
    }

    private static String hostOf(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT).trim();
        int colon = host.lastIndexOf(':');
        return (colon > -1 && host.indexOf(']') < colon) ? host.substring(0, colon) : host;
    }

    private static boolean isUuid(String s) {
        if (s.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class UnknownTenantException extends RuntimeException {
        UnknownTenantException() {
            super(null, null, false, false);
        }
    }
}
