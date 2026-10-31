package app.basera.tenancy;

import java.util.UUID;

/**
 * What the request host (or the dev X-Tenant-ID header) resolved to:
 * <ul>
 *   <li>TENANT   - a college subdomain (gec-jabalpur.basera.app)</li>
 *   <li>PLATFORM - the SUPER_ADMIN host (admin.basera.app)</li>
 *   <li>AUTH     - the central login host (auth.basera.app), serves only the Google OAuth endpoints</li>
 *   <li>NONE     - anything else (apex domain, unknown host)</li>
 * </ul>
 */
public record TenantContext(Kind kind, UUID tenantId, String slug) {

    public enum Kind { TENANT, PLATFORM, AUTH, NONE }

    public static final TenantContext PLATFORM = new TenantContext(Kind.PLATFORM, null, null);
    public static final TenantContext AUTH = new TenantContext(Kind.AUTH, null, null);
    public static final TenantContext NONE = new TenantContext(Kind.NONE, null, null);

    public static TenantContext tenant(UUID tenantId, String slug) {
        return new TenantContext(Kind.TENANT, tenantId, slug);
    }

    public boolean isTenant() {
        return kind == Kind.TENANT;
    }
}
