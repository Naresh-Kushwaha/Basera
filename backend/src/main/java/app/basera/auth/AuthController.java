package app.basera.auth;

import app.basera.auth.dto.TokenRequest;
import app.basera.auth.dto.TokenResponse;
import app.basera.common.error.ApiError;
import app.basera.common.error.ApiException;
import app.basera.security.JwtService;
import app.basera.tenancy.TenantContextHolder;
import app.basera.tenancy.TenantInfo;
import app.basera.tenancy.TenantLookupService;
import app.basera.tenancy.TenantUrls;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login flow:
 *  1. SPA at <slug>.basera.app  -> navigates to https://auth.basera.app/api/v1/auth/login?tenant=<slug>
 *  2. /login  -> redirects to Google (signed state + nonce cookie)
 *  3. Google  -> https://auth.basera.app/api/v1/auth/google/callback  (the ONE registered redirect URI)
 *  4. /callback validates everything, creates the one-time code, redirects to <slug>.basera.app/auth/callback?code=...
 *  5. SPA     -> POST /api/v1/auth/token {code} on its OWN host; gets access token (+ refresh cookie)
 * /login and /callback exist only on the auth host; the rest only on tenant/platform hosts
 * (enforced by TenantResolutionFilter).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String PLATFORM_STATE = "_platform";

    private final GoogleOAuthClient google;
    private final LoginService loginService;
    private final SessionService sessions;
    private final JwtService jwt;
    private final TenantLookupService tenants;
    private final TenantUrls urls;
    private final RefreshCookies cookies;
    private final OriginGuard originGuard;
    private final SecureRandom random = new SecureRandom();

    public AuthController(GoogleOAuthClient google, LoginService loginService, SessionService sessions,
                          JwtService jwt, TenantLookupService tenants, TenantUrls urls, RefreshCookies cookies,
                          OriginGuard originGuard) {
        this.google = google;
        this.loginService = loginService;
        this.sessions = sessions;
        this.jwt = jwt;
        this.tenants = tenants;
        this.urls = urls;
        this.cookies = cookies;
        this.originGuard = originGuard;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login(@RequestParam(name = "tenant", required = false) String tenant) {
        String stateTenant = PLATFORM_STATE;
        String hint = null;
        if (tenant != null && !tenant.isBlank()) {
            String slug = tenant.trim().toLowerCase(Locale.ROOT);
            TenantInfo info = tenants.findBySlug(slug).filter(TenantInfo::active)
                    .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Unknown college"));
            stateTenant = info.slug();
            hint = "DOMAIN_ONLY".equals(info.joinPolicy()) ? info.emailDomain() : null;
        }
        String nonce = newNonce();
        String state = jwt.issueStateToken(stateTenant, nonce);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(google.authorizationUrl(state, nonce, hint)))
                .header(HttpHeaders.SET_COOKIE, cookies.oauthNonce(nonce).toString())
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .build();
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @CookieValue(name = RefreshCookies.OAUTH_NONCE_NAME, required = false) String nonceCookie) {

        JwtService.OAuthState parsed;
        try {
            parsed = jwt.parseState(state == null ? "" : state);
        } catch (JwtException e) {
            throw ApiException.badRequest("INVALID_STATE", "The login attempt is invalid or has expired");
        }
        if (nonceCookie == null || !nonceCookie.equals(parsed.nonce())) {
            throw ApiException.badRequest("INVALID_STATE", "The login attempt does not belong to this browser");
        }

        String base;
        String tenantSlug;
        if (PLATFORM_STATE.equals(parsed.tenant())) {
            base = urls.platformBase();
            tenantSlug = null;
        } else {
            TenantInfo info = tenants.findBySlug(parsed.tenant()).filter(TenantInfo::active)
                    .orElseThrow(() -> ApiException.badRequest("INVALID_STATE", "Unknown college"));
            base = urls.tenantBase(info.slug());
            tenantSlug = info.slug();
        }

        if (error != null || code == null || code.isBlank()) {
            return redirect(base, "error=access_denied");
        }
        try {
            GoogleProfile profile = google.exchange(code, parsed.nonce());
            String oneTimeCode = loginService.completeLogin(profile, tenantSlug);
            return redirect(base, "code=" + URLEncoder.encode(oneTimeCode, StandardCharsets.UTF_8));
        } catch (LoginDeniedException e) {
            return redirect(base, "error=" + URLEncoder.encode(e.code(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return redirect(base, "error=login_failed");
        }
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        originGuard.check(http);
        SessionService.SessionResult result = sessions.exchange(request.code(), TenantContextHolder.get());
        return sessionResponse(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = RefreshCookies.REFRESH_NAME, required = false) String refreshToken,
            HttpServletRequest http) {
        originGuard.check(http);
        try {
            return sessionResponse(sessions.refresh(refreshToken, TenantContextHolder.get()));
        } catch (ApiException e) {
            // Any refresh failure also expires the cookie, so the SPA falls back to the login screen.
            ApiError body = new ApiError(Instant.now(), e.status().value(), e.code(), e.getMessage(),
                    http.getRequestURI(), MDC.get("traceId"), null);
            return ResponseEntity.status(e.status())
                    .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString())
                    .body(body);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookies.REFRESH_NAME, required = false) String refreshToken,
            HttpServletRequest http) {
        originGuard.check(http);
        sessions.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString()).build();
    }

    // ---------------------------------------------------------------------------------------------------

    private ResponseEntity<TokenResponse> sessionResponse(SessionService.SessionResult result) {
        ResponseCookie cookie = cookies.refresh(result.refreshToken(), result.refreshTtl());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .body(result.body());
    }

    private ResponseEntity<Void> redirect(String base, String query) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(base + "/auth/callback?" + query))
                .header(HttpHeaders.SET_COOKIE, cookies.clearOauthNonce().toString())
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .build();
    }

    private String newNonce() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
