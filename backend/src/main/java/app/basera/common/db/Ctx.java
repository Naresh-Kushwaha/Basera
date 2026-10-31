package app.basera.common.db;

import app.basera.security.AuthenticatedUser;
import app.basera.security.CurrentUser;
import app.basera.tenancy.TenantContextHolder;
import java.util.UUID;

/** Shorthand for "who is calling, in which college". */
public final class Ctx {

    private Ctx() {}

    public static AuthenticatedUser me() {
        return CurrentUser.require();
    }

    public static UUID userId() {
        return me().userId();
    }

    public static UUID membershipId() {
        return me().membershipId();
    }

    public static UUID tenantId() {
        return TenantContextHolder.requireTenant().tenantId();
    }

    public static boolean isAdmin() {
        return me().role() == app.basera.security.Role.TENANT_ADMIN;
    }
}
