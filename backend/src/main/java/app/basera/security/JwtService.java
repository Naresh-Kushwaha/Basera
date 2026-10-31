package app.basera.security;

import app.basera.config.BaseraProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies Basera's own HS256 tokens. Two token kinds share one key but are NOT interchangeable
 * (claim "typ"): "access" (API bearer token) and "oauth_state" (the signed state of the Google login).
 * One service both signs and verifies, so a symmetric key is appropriate; rotate by deploying a new secret
 * (all sessions then refresh, since refresh tokens are opaque DB rows and unaffected).
 */
@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_STATE = "oauth_state";

    private final NimbusJwtEncoder encoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder stateDecoder;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration stateTtl;

    public JwtService(BaseraProperties props) {
        String secret = props.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "basera.jwt.secret (BASERA_JWT_SECRET) must be set and at least 32 bytes long");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.issuer = props.jwt().issuer();
        this.accessTtl = props.jwt().accessTtl();
        this.stateTtl = props.auth().stateTtl();
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessDecoder = decoder(key, TYPE_ACCESS);
        this.stateDecoder = decoder(key, TYPE_STATE);
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public JwtDecoder accessTokenDecoder() {
        return accessDecoder;
    }

    /** tenantId / membershipId are null for SUPER_ADMIN sessions. */
    public String issueAccessToken(UUID userId, Role role, UUID tenantId, UUID membershipId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .id(UUID.randomUUID().toString())
                .claim("typ", TYPE_ACCESS)
                .claim("role", role.name());
        if (tenantId != null) {
            claims.claim("tid", tenantId.toString());
        }
        if (membershipId != null) {
            claims.claim("mid", membershipId.toString());
        }
        return encode(claims.build());
    }

    public String issueStateToken(String tenant, String nonce) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(stateTtl))
                .claim("typ", TYPE_STATE)
                .claim("tenant", tenant)
                .claim("nonce", nonce)
                .build();
        return encode(claims);
    }

    /** Throws JwtException if the state is forged, expired, or not a state token. */
    public OAuthState parseState(String token) {
        Jwt jwt = stateDecoder.decode(token);
        String tenant = jwt.getClaimAsString("tenant");
        String nonce = jwt.getClaimAsString("nonce");
        if (tenant == null || nonce == null) {
            throw new JwtException("incomplete state");
        }
        return new OAuthState(tenant, nonce);
    }

    public record OAuthState(String tenant, String nonce) {}

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JwtDecoder decoder(SecretKey key, String expectedType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> typeCheck = jwt -> expectedType.equals(jwt.getClaimAsString("typ"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong token type", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), typeCheck));
        return decoder;
    }
}
