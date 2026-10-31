package app.basera.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns each request an id (reused from X-Request-Id only if it is a safe token), exposes it in MDC as
 * "traceId" for logs and in the standard error body, and echoes it back in the response header.
 * Instantiated by SecurityConfig (deliberately NOT a bean, so it is not registered twice).
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String id = (incoming != null && SAFE.matcher(incoming).matches()) ? incoming : UUID.randomUUID().toString();
        MDC.put("traceId", id);
        response.setHeader("X-Request-Id", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
