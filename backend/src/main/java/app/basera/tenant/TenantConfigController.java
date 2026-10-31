package app.basera.tenant;

import app.basera.common.error.ApiException;
import app.basera.tenancy.TenantContext;
import app.basera.tenancy.TenantContextHolder;
import app.basera.tenancy.TenantLookupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant-config")
public class TenantConfigController {

    private final TenantLookupService tenants;

    public TenantConfigController(TenantLookupService tenants) {
        this.tenants = tenants;
    }

    @GetMapping
    public PublicTenantConfig get() {
        TenantContext ctx = TenantContextHolder.requireTenant();
        return tenants.findById(ctx.tenantId())
                .map(PublicTenantConfig::from)
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Unknown college"));
    }
}
