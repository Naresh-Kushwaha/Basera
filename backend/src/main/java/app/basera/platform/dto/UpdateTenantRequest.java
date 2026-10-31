package app.basera.platform.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Every field optional: only the fields present are changed. An empty emailDomain clears it. */
public record UpdateTenantRequest(
        @Size(min = 1, max = 150) String name,
        @Pattern(regexp = "^$|^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$") String emailDomain,
        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String defaultLanguage,
        @Size(max = 10) List<@Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String> languages,
        @Size(max = 20) Map<String, String> branding,
        @Pattern(regexp = "^(OPEN|DOMAIN_ONLY)$") String joinPolicy,
        Boolean requireVerifiedStudent,
        Boolean active) {}
