package app.basera.tenant;

import app.basera.tenancy.TenantInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the SPA needs before login: theme, enabled modules, languages, campus point. Nothing sensitive. */
public record PublicTenantConfig(
        UUID id,
        String slug,
        String name,
        String cityName,
        double campusLat,
        double campusLng,
        String defaultLanguage,
        List<String> languages,
        Map<String, Object> branding,
        List<String> enabledModules,
        String joinPolicy,
        String emailDomain,
        boolean requireVerifiedStudent) {

    public static PublicTenantConfig from(TenantInfo t) {
        return new PublicTenantConfig(t.id(), t.slug(), t.name(), t.cityName(), t.campusLat(), t.campusLng(),
                t.defaultLanguage(), t.languages(), t.branding(), t.enabledModules(), t.joinPolicy(),
                t.emailDomain(), t.requireVerifiedStudent());
    }
}
