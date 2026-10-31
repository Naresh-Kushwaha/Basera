package app.basera.platform;

import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import app.basera.platform.dto.AdminInvitationRequest;
import app.basera.platform.dto.CityResponse;
import app.basera.platform.dto.CreateCityRequest;
import app.basera.platform.dto.CreateTenantRequest;
import app.basera.platform.dto.CreateTenantResponse;
import app.basera.platform.dto.InvitationSummary;
import app.basera.platform.dto.PlatformMeResponse;
import app.basera.platform.dto.UpdateModulesRequest;
import app.basera.platform.dto.UpdateTenantRequest;
import app.basera.tenancy.TenantInfo;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN API (admin.<base-domain>). SecurityConfig also enforces hasRole('SUPER_ADMIN') on /platform/**. */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final PlatformService service;

    public PlatformController(PlatformService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public PlatformMeResponse me() {
        return service.me();
    }

    @GetMapping("/cities")
    public List<CityResponse> cities() {
        return service.listCities();
    }

    @PostMapping("/cities")
    @ResponseStatus(HttpStatus.CREATED)
    public CityResponse createCity(@Valid @RequestBody CreateCityRequest request) {
        return service.createCity(request);
    }

    @GetMapping("/tenants")
    public PageResponse<TenantInfo> tenants(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        var p = Paging.of(page, size);
        return service.listTenants(p.getPageNumber(), p.getPageSize());
    }

    @GetMapping("/tenants/{id}")
    public TenantInfo tenant(@PathVariable UUID id) {
        return service.getTenant(id);
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateTenantResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return service.createTenant(request);
    }

    @PatchMapping("/tenants/{id}")
    public TenantInfo updateTenant(@PathVariable UUID id, @Valid @RequestBody UpdateTenantRequest request) {
        return service.updateTenant(id, request);
    }

    @PutMapping("/tenants/{id}/modules")
    public TenantInfo updateModules(@PathVariable UUID id, @Valid @RequestBody UpdateModulesRequest request) {
        return service.updateModules(id, request.modules());
    }

    @PostMapping("/tenants/{id}/admin-invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationSummary inviteAdmin(@PathVariable UUID id, @Valid @RequestBody AdminInvitationRequest request) {
        return service.inviteAdmin(id, request.email());
    }
}
