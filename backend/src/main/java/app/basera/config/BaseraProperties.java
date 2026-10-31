package app.basera.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All Basera settings, bound from the "basera.*" keys in application.yml / environment variables. */
@ConfigurationProperties(prefix = "basera")
public record BaseraProperties(
        @DefaultValue Tenant tenant,
        @DefaultValue Jwt jwt,
        @DefaultValue Google google,
        @DefaultValue Auth auth,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Platform platform) {

    public record Tenant(
            @DefaultValue("localhost") String baseDomain,
            @DefaultValue("http") String urlScheme,
            @DefaultValue("") String publicPortSuffix,
            @DefaultValue("false") boolean headerEnabled) {}

    public record Jwt(
            String secret,
            @DefaultValue("basera") String issuer,
            @DefaultValue("PT15M") Duration accessTtl,
            @DefaultValue("P14D") Duration refreshTtl) {}

    public record Google(@DefaultValue("") String clientId, @DefaultValue("") String clientSecret) {}

    public record Auth(
            @DefaultValue("true") boolean cookieSecure,
            @DefaultValue("PT60S") Duration authCodeTtl,
            @DefaultValue("PT10M") Duration stateTtl,
            @DefaultValue("false") boolean devLoginEnabled) {}

    public record RateLimit(
            @DefaultValue("30") int authPerMinute,
            @DefaultValue("120") int publicPerMinute,
            @DefaultValue("false") boolean trustForwardedFor) {}

    public record Platform(@DefaultValue List<String> superAdminEmails) {}
}
