package app.basera.auth;

import app.basera.config.BaseraProperties;
import app.basera.tenancy.TenantUrls;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Google OpenID Connect (authorization-code flow), done by hand so the whole handshake is stateless:
 * the "state" is a signed JWT and the nonce is bound to the browser with a short-lived HttpOnly cookie,
 * so no server session is needed and any replica can finish the login.
 */
@Component
public class GoogleOAuthClient {

    private static final String AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final BaseraProperties props;
    private final TenantUrls urls;
    private final RestClient rest = RestClient.create();
    private final JwtDecoder idTokenDecoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();

    public GoogleOAuthClient(BaseraProperties props, TenantUrls urls) {
        this.props = props;
        this.urls = urls;
    }

    /** The single redirect URI to register in Google Cloud Console (no per-college registration needed). */
    public String redirectUri() {
        return urls.authBase() + "/api/v1/auth/google/callback";
    }

    public String authorizationUrl(String state, String nonce, String hostedDomainHint) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", props.google().clientId());
        q.put("redirect_uri", redirectUri());
        q.put("response_type", "code");
        q.put("scope", "openid email profile");
        q.put("state", state);
        q.put("nonce", nonce);
        q.put("prompt", "select_account");
        if (hostedDomainHint != null && !hostedDomainHint.isBlank()) {
            q.put("hd", hostedDomainHint);   // a UI hint only; the server re-checks the email domain
        }
        String query = q.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return AUTH_URI + "?" + query;
    }

    /** Exchanges the code and fully validates the ID token (signature, issuer, audience, expiry, nonce). */
    public GoogleProfile exchange(String code, String expectedNonce) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", props.google().clientId());
        form.add("client_secret", props.google().clientSecret());
        form.add("redirect_uri", redirectUri());
        form.add("grant_type", "authorization_code");

        Map<String, Object> response;
        try {
            response = rest.post().uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException e) {
            throw new LoginDeniedException("google_exchange_failed");
        }
        Object idToken = response == null ? null : response.get("id_token");
        if (!(idToken instanceof String token)) {
            throw new LoginDeniedException("google_exchange_failed");
        }

        Jwt jwt;
        try {
            jwt = idTokenDecoder.decode(token);   // signature + expiry
        } catch (JwtException e) {
            throw new LoginDeniedException("invalid_id_token");
        }
        Object iss = jwt.getClaim("iss");
        if (iss == null || !ISSUERS.contains(iss.toString())) {
            throw new LoginDeniedException("invalid_id_token");
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(props.google().clientId())) {
            throw new LoginDeniedException("invalid_id_token");
        }
        if (!expectedNonce.equals(jwt.getClaimAsString("nonce"))) {
            throw new LoginDeniedException("invalid_id_token");
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new LoginDeniedException("email_missing");
        }
        Object verified = jwt.getClaim("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
        return new GoogleProfile(
                jwt.getSubject(),
                email.toLowerCase(),
                emailVerified,
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("picture"),
                jwt.getClaimAsString("hd"));
    }
}
