package app.basera.auth;

import app.basera.auth.dto.DevLoginRequest;
import app.basera.auth.dto.TokenResponse;
import app.basera.config.BaseraProperties;
import app.basera.tenancy.TenantContext;
import app.basera.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL DEVELOPMENT ONLY: signs in without Google so the API can be exercised before OAuth credentials exist.
 * The bean is created only when BOTH the "dev" profile is active AND basera.auth.dev-login-enabled=true.
 * Production never sets either, so this endpoint does not exist there.
 */
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
@ConditionalOnProperty(prefix = "basera.auth", name = "dev-login-enabled", havingValue = "true")
public class DevLoginController {

    private final LoginService loginService;
    private final SessionService sessions;
    private final RefreshCookies cookies;

    public DevLoginController(LoginService loginService, SessionService sessions, RefreshCookies cookies,
                              BaseraProperties props) {
        this.loginService = loginService;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody DevLoginRequest request) {
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = loginService.devProvision(request.email(), request.name(), request.role(), ctx);
        SessionService.SessionResult result = sessions.startSession(userId, ctx);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken(), result.refreshTtl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .body(result.body());
    }
}
