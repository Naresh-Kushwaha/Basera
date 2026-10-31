package app.basera.platform.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateTenantRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$",
                message = "3-63 chars: lowercase letters, digits and hyphens") String slug,
        @NotNull UUID cityId,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal campusLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal campusLng,
        @Pattern(regexp = "^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$", message = "lowercase domain, e.g. gecj.ac.in")
        String emailDomain,
        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String defaultLanguage,
        @Size(max = 10) List<@Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String> languages,
        @Size(max = 20) Map<String, String> branding,
        List<String> enabledModules,
        @Pattern(regexp = "^(OPEN|DOMAIN_ONLY)$") String joinPolicy,
        Boolean requireVerifiedStudent,
        @NotBlank @Email @Size(max = 200) String adminEmail) {}
