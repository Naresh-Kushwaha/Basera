package app.basera.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.basera.config.BaseraProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTest {

    static BaseraProperties props(String secret) {
        return new BaseraProperties(
                new BaseraProperties.Tenant("basera.test", "http", "", false),
                new BaseraProperties.Jwt(secret, "basera", Duration.ofMinutes(15), Duration.ofDays(14)),
                new BaseraProperties.Google("", ""),
                new BaseraProperties.Auth(false, Duration.ofSeconds(60), Duration.ofMinutes(10), false),
                new BaseraProperties.RateLimit(30, 120, false),
                new BaseraProperties.Platform(List.of()));
    }

    private final JwtService service = new JwtService(props("unit-test-secret-unit-test-secret-1234"));

    @Test
    void accessTokenRoundTripsItsClaims() {
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID(), member = UUID.randomUUID();
        String token = service.issueAccessToken(user, Role.OWNER, tenant, member);

        Jwt decoded = service.accessTokenDecoder().decode(token);
        AuthenticatedUser who = AuthenticatedUser.from(decoded);
        assertThat(who.userId()).isEqualTo(user);
        assertThat(who.role()).isEqualTo(Role.OWNER);
        assertThat(who.tenantId()).isEqualTo(tenant);
        assertThat(who.membershipId()).isEqualTo(member);
    }

    @Test
    void superAdminTokensCarryNoTenant() {
        String token = service.issueAccessToken(UUID.randomUUID(), Role.SUPER_ADMIN, null, null);
        AuthenticatedUser who = AuthenticatedUser.from(service.accessTokenDecoder().decode(token));
        assertThat(who.tenantId()).isNull();
        assertThat(who.membershipId()).isNull();
    }

    @Test
    void stateAndAccessTokensAreNotInterchangeable() {
        String state = service.issueStateToken("gec-a", "n-1");
        String access = service.issueAccessToken(UUID.randomUUID(), Role.STUDENT, UUID.randomUUID(), UUID.randomUUID());

        assertThrows(JwtException.class, () -> service.accessTokenDecoder().decode(state));
        assertThrows(JwtException.class, () -> service.parseState(access));
        assertThat(service.parseState(state).tenant()).isEqualTo("gec-a");
        assertThat(service.parseState(state).nonce()).isEqualTo("n-1");
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        JwtService other = new JwtService(props("another-secret-another-secret-12345678"));
        String forged = other.issueAccessToken(UUID.randomUUID(), Role.TENANT_ADMIN, UUID.randomUUID(), UUID.randomUUID());
        assertThrows(JwtException.class, () -> service.accessTokenDecoder().decode(forged));
    }

    @Test
    void aTamperedTokenIsRejected() {
        String token = service.issueAccessToken(UUID.randomUUID(), Role.STUDENT, UUID.randomUUID(), UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");
        assertThrows(JwtException.class, () -> service.accessTokenDecoder().decode(tampered));
    }

    @Test
    void aShortSecretFailsStartup() {
        assertThrows(IllegalStateException.class, () -> new JwtService(props("too-short")));
        assertThrows(IllegalStateException.class, () -> new JwtService(props(null)));
    }
}
