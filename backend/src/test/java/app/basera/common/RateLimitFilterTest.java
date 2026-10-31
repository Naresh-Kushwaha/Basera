package app.basera.common;

import static org.assertj.core.api.Assertions.assertThat;

import app.basera.common.error.ErrorResponseWriter;
import app.basera.common.web.RateLimitFilter;
import app.basera.config.BaseraProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private final ErrorResponseWriter writer = new ErrorResponseWriter(new ObjectMapper().findAndRegisterModules());

    private int call(RateLimitFilter f, String path, String ip, String xff) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRequestURI(path);
        req.setRemoteAddr(ip);
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void publicAuthEndpointsAreLimitedPerClientIp() throws Exception {
        RateLimitFilter f = new RateLimitFilter(new BaseraProperties.RateLimit(2, 5, false), writer);
        assertThat(call(f, "/api/v1/auth/refresh", "10.0.0.1", null)).isEqualTo(200);
        assertThat(call(f, "/api/v1/auth/refresh", "10.0.0.1", null)).isEqualTo(200);
        assertThat(call(f, "/api/v1/auth/refresh", "10.0.0.1", null)).isEqualTo(429);
        assertThat(call(f, "/api/v1/auth/refresh", "10.0.0.2", null)).isEqualTo(200);   // other clients unaffected
    }

    @Test
    void otherEndpointsAreNotRateLimitedByThisFilter() throws Exception {
        RateLimitFilter f = new RateLimitFilter(new BaseraProperties.RateLimit(1, 1, false), writer);
        for (int i = 0; i < 5; i++) {
            assertThat(call(f, "/api/v1/me", "10.0.0.1", null)).isEqualTo(200);
        }
    }

    @Test
    void forwardedForIsHonouredOnlyWhenTrusted() throws Exception {
        RateLimitFilter untrusted = new RateLimitFilter(new BaseraProperties.RateLimit(1, 1, false), writer);
        assertThat(call(untrusted, "/api/v1/auth/token", "10.0.0.1", "1.1.1.1")).isEqualTo(200);
        assertThat(call(untrusted, "/api/v1/auth/token", "10.0.0.1", "2.2.2.2")).isEqualTo(429);   // spoofing XFF does not help

        RateLimitFilter trusted = new RateLimitFilter(new BaseraProperties.RateLimit(1, 1, true), writer);
        assertThat(call(trusted, "/api/v1/auth/token", "10.0.0.1", "1.1.1.1")).isEqualTo(200);
        assertThat(call(trusted, "/api/v1/auth/token", "10.0.0.1", "2.2.2.2")).isEqualTo(200);
    }
}
