package app.basera.tenancy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable snapshot of a tenant row (plus its city name), cached in-process by TenantLookupService. */
public record TenantInfo(
        UUID id,
        String slug,
        String name,
        UUID cityId,
        String cityName,
        double campusLat,
        double campusLng,
        String emailDomain,
        String defaultLanguage,
        List<String> languages,
        Map<String, Object> branding,
        List<String> enabledModules,
        String joinPolicy,
        boolean requireVerifiedStudent,
        boolean active) {

    public boolean moduleEnabled(String module) {
        return enabledModules.contains(module);
    }
}
