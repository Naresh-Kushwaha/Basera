package app.basera.auth;

import app.basera.config.BaseraProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Refresh token cookie: HttpOnly (JavaScript can never read it), Secure, SameSite=Strict, HOST-ONLY (no Domain
 * attribute) and scoped to /api/v1/auth. Because the API lives under each tenant host, a session on one college's
 * host is physically unavailable to another college's host.
 */
@Component
public class RefreshCookies {

    public static final String REFRESH_NAME = "bsr_rt";
    public static final String OAUTH_NONCE_NAME = "bsr_oauth";
    private static final String PATH = "/api/v1/auth";

    private final BaseraProperties props;

    public RefreshCookies(BaseraProperties props) {
        this.props = props;
    }

    public ResponseCookie refresh(String token, Duration ttl) {
        return ResponseCookie.from(REFRESH_NAME, token)
                .httpOnly(true).secure(props.auth().cookieSecure()).sameSite("Strict")
                .path(PATH).maxAge(ttl).build();
    }

    public ResponseCookie clearRefresh() {
        return ResponseCookie.from(REFRESH_NAME, "")
                .httpOnly(true).secure(props.auth().cookieSecure()).sameSite("Strict")
                .path(PATH).maxAge(0).build();
    }

    /** Binds the OAuth handshake to the browser that started it (login-CSRF protection). Lax: Google redirects back top-level. */
    public ResponseCookie oauthNonce(String nonce) {
        return ResponseCookie.from(OAUTH_NONCE_NAME, nonce)
                .httpOnly(true).secure(props.auth().cookieSecure()).sameSite("Lax")
                .path(PATH).maxAge(props.auth().stateTtl()).build();
    }

    public ResponseCookie clearOauthNonce() {
        return ResponseCookie.from(OAUTH_NONCE_NAME, "")
                .httpOnly(true).secure(props.auth().cookieSecure()).sameSite("Lax")
                .path(PATH).maxAge(0).build();
    }
}
