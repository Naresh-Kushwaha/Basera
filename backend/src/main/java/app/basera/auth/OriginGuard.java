package app.basera.auth;

import app.basera.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * CSRF defence for the cookie-authenticated endpoints (token / refresh / logout). Browsers always send Origin on cross-origin
 * POSTs; if it is present it must match the host the request was addressed to (host AND port, with the scheme's default port
 * implied when the Host header has none, e.g. "college.basera.app" matches "https://college.basera.app").
 * Requests without Origin (curl, mobile clients) are allowed: they cannot be driven by a victim's browser.
 */
@Component
public class OriginGuard {

    public void check(HttpServletRequest request) {
        if (!allowed(request.getHeader("Origin"), request.getHeader("Host"))) {
            throw ApiException.forbidden("BAD_ORIGIN", "Cross-origin request rejected");
        }
    }

    static boolean allowed(String origin, String hostHeader) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (uri.getHost() == null || uri.getScheme() == null || hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int originPort = effectivePort(uri.getPort(), scheme);

        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        int hostPort = -1;
        int colon = host.lastIndexOf(':');
        if (colon > -1 && host.indexOf(']') < colon) {          // not part of an IPv6 literal
            try {
                hostPort = Integer.parseInt(host.substring(colon + 1));
            } catch (NumberFormatException e) {
                return false;
            }
            host = host.substring(0, colon);
        }
        return uri.getHost().toLowerCase(Locale.ROOT).equals(host) && originPort == effectivePort(hostPort, scheme);
    }

    private static int effectivePort(int port, String scheme) {
        if (port != -1) {
            return port;
        }
        return "https".equals(scheme) ? 443 : 80;
    }
}
