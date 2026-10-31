package app.basera.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.basera.common.error.ErrorResponseWriter;
import app.basera.config.BaseraProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantResolutionFilterTest {

    private static final UUID ID_A = UUID.randomUUID();

    private TenantLookupService lookup;
    private final ErrorResponseWriter writer = new ErrorResponseWriter(new ObjectMapper().findAndRegisterModules());

    @BeforeEach
    void setUp() {
        lookup = mock(TenantLookupService.class);
        when(lookup.findBySlug(anyString())).thenReturn(Optional.empty());
        when(lookup.findBySlug("gec-a")).thenReturn(Optional.of(info(ID_A, "gec-a", true)));
        when(lookup.findBySlug("closed")).thenReturn(Optional.of(info(UUID.randomUUID(), "closed", false)));
        when(lookup.findById(ID_A)).thenReturn(Optional.of(info(ID_A, "gec-a", true)));
    }

    private static TenantInfo info(UUID id, String slug, boolean active) {
        return new TenantInfo(id, slug, slug, UUID.randomUUID(), "City", 0, 0, null, "en", List.of("en"), Map.of(),
                List.of(), "OPEN", false, active);
    }

    private TenantResolutionFilter filter(boolean headerEnabled) {
        return new TenantResolutionFilter(lookup,
                new BaseraProperties.Tenant("basera.test", "http", "", headerEnabled), writer);
    }

    /** Runs the filter and returns the context a controller would have seen (null if the request was rejected). */
    private record Outcome(int status, TenantContext seen) {}

    private Outcome run(TenantResolutionFilter f, String host, String path, String tenantHeader) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        if (host != null) {
            req.addHeader("Host", host);
        }
        if (tenantHeader != null) {
            req.addHeader("X-Tenant-ID", tenantHeader);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<TenantContext> seen = new AtomicReference<>();
        f.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                seen.set(TenantContextHolder.get());
            }
        });
        return new Outcome(res.getStatus(), seen.get());
    }

    @Test
    void collegeSubdomainResolvesToATenantContext() throws Exception {
        Outcome o = run(filter(false), "gec-a.basera.test:8443", "/api/v1/me", null);
        assertThat(o.seen().kind()).isEqualTo(TenantContext.Kind.TENANT);
        assertThat(o.seen().tenantId()).isEqualTo(ID_A);
        assertThat(TenantContextHolder.get().kind()).isEqualTo(TenantContext.Kind.NONE);   // cleared afterwards
    }

    @Test
    void unknownOrInactiveCollegeIs404() throws Exception {
        assertThat(run(filter(false), "ghost.basera.test", "/api/v1/me", null).status()).isEqualTo(404);
        assertThat(run(filter(false), "closed.basera.test", "/api/v1/me", null).status()).isEqualTo(404);
    }

    @Test
    void apexAndForeignHostsAreRejectedAs400() throws Exception {
        assertThat(run(filter(false), "basera.test", "/api/v1/me", null).status()).isEqualTo(400);
        assertThat(run(filter(false), "evil.example.com", "/api/v1/me", null).status()).isEqualTo(400);
        assertThat(run(filter(false), "a.b.basera.test", "/api/v1/me", null).status()).isEqualTo(400);   // no nested labels
    }

    @Test
    void headerIsIgnoredUnlessExplicitlyEnabled() throws Exception {
        assertThat(run(filter(false), "basera.test", "/api/v1/me", ID_A.toString()).status()).isEqualTo(400);
        Outcome o = run(filter(true), "localhost", "/api/v1/me", ID_A.toString());
        assertThat(o.seen().tenantId()).isEqualTo(ID_A);
        Outcome bySlug = run(filter(true), "localhost", "/api/v1/me", "gec-a");
        assertThat(bySlug.seen().tenantId()).isEqualTo(ID_A);
    }

    @Test
    void authHostServesOnlyTheGoogleLoginEndpoints() throws Exception {
        assertThat(run(filter(false), "auth.basera.test", "/api/v1/auth/login", null).seen().kind())
                .isEqualTo(TenantContext.Kind.AUTH);
        assertThat(run(filter(false), "auth.basera.test", "/api/v1/auth/google/callback", null).status()).isEqualTo(200);
        assertThat(run(filter(false), "auth.basera.test", "/api/v1/me", null).status()).isEqualTo(404);
        assertThat(run(filter(false), "auth.basera.test", "/api/v1/auth/refresh", null).status()).isEqualTo(404);
    }

    @Test
    void googleLoginEndpointsDoNotExistOnCollegeOrPlatformHosts() throws Exception {
        assertThat(run(filter(false), "gec-a.basera.test", "/api/v1/auth/login", null).status()).isEqualTo(404);
        assertThat(run(filter(false), "admin.basera.test", "/api/v1/auth/google/callback", null).status()).isEqualTo(404);
    }

    @Test
    void platformApiExistsOnlyOnTheAdminHost() throws Exception {
        assertThat(run(filter(false), "admin.basera.test", "/api/v1/platform/tenants", null).seen().kind())
                .isEqualTo(TenantContext.Kind.PLATFORM);
        assertThat(run(filter(false), "gec-a.basera.test", "/api/v1/platform/tenants", null).status()).isEqualTo(404);
        assertThat(run(filter(false), "admin.basera.test", "/api/v1/admin/members", null).status()).isEqualTo(404);
    }
}
