package app.basera.common.db;

import app.basera.common.error.ApiException;
import app.basera.tenancy.TenantInfo;
import app.basera.tenancy.TenantLookupService;
import org.springframework.stereotype.Component;

/** Per-tenant feature toggles: a disabled module answers 403 MODULE_DISABLED. */
@Component
public class Modules {

    private final TenantLookupService tenants;

    public Modules(TenantLookupService tenants) {
        this.tenants = tenants;
    }

    public void require(String module) {
        TenantInfo t = tenants.findById(Ctx.tenantId())
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Unknown college"));
        if (!t.moduleEnabled(module)) {
            throw ApiException.forbidden("MODULE_DISABLED", "This feature is not enabled for your college");
        }
    }
}
