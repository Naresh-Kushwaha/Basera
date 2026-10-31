package app.basera.common.web;

import app.basera.common.error.ErrorResponseWriter;
import app.basera.config.BaseraProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window, per-client-IP rate limit for the public endpoints (auth + tenant-config).
 * LIMITATION: counters live in this JVM only, so with N replicas the effective limit is up to N x the
 * configured value. That is acceptable as abuse protection for now; a shared limiter (Redis) is the
 * documented upgrade once we run multiple replicas.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final BaseraProperties.RateLimit props;
    private final ErrorResponseWriter writer;
    private final Cache<String, AtomicInteger> authWindow =
            Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(Duration.ofMinutes(1)).build();
    private final Cache<String, AtomicInteger> publicWindow =
            Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(Duration.ofMinutes(1)).build();

    public RateLimitFilter(BaseraProperties.RateLimit props, ErrorResponseWriter writer) {
        this.props = props;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Cache<String, AtomicInteger> window;
        int limit;
        if (path.startsWith("/api/v1/auth/")) {
            window = authWindow;
            limit = props.authPerMinute();
        } else if (path.equals("/api/v1/tenant-config")) {
            window = publicWindow;
            limit = props.publicPerMinute();
        } else {
            chain.doFilter(request, response);
            return;
        }
        int count = window.get(clientIp(request), k -> new AtomicInteger()).incrementAndGet();
        if (count > limit) {
            response.setHeader("Retry-After", "60");
            writer.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests, please slow down");
            return;
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        if (props.trustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
