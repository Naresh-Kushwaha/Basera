package app.basera.tenancy;

import static app.basera.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.basera.security.JwtService;
import app.basera.security.Role;
import app.basera.support.TestData;
import app.basera.support.TestPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end through the real filter chain, JPA, tenant-aware transaction manager and PostgreSQL RLS.
 * Hosts are <slug>.basera.test, exactly how production resolves tenants.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantApiIT {

    private static final String HOST_A = "gec-a.basera.test";
    private static final String HOST_B = "gec-b.basera.test";
    private static final String HOST_PLATFORM = "admin.basera.test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String url = TestPostgres.jdbcUrl();
        r.add("basera.datasource.app.jdbc-url", () -> url);
        r.add("basera.datasource.app.username", () -> "basera_app");
        r.add("basera.datasource.app.password", () -> "app");
        r.add("basera.datasource.platform.jdbc-url", () -> url);
        r.add("basera.datasource.platform.username", () -> "basera_platform");
        r.add("basera.datasource.platform.password", () -> "platform");
        r.add("spring.flyway.url", () -> url);
        r.add("spring.flyway.user", () -> "basera_owner");
        r.add("spring.flyway.password", () -> "owner");
        r.add("basera.jwt.secret", () -> "test-secret-test-secret-test-secret-123456");
        r.add("basera.tenant.base-domain", () -> "basera.test");
        r.add("basera.auth.cookie-secure", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired TenantLookupService tenants;

    @BeforeEach
    void seed() throws SQLException {
        TestData.reset();
        tenants.evictAll();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String token(UUID user, Role role, UUID tenant, UUID membership) {
        return "Bearer " + jwt.issueAccessToken(user, role, tenant, membership);
    }

    private String adminA() { return token(USER_ADMIN_A, Role.TENANT_ADMIN, TENANT_A, MEMBER_ADMIN_A); }

    private String studentA() { return token(USER_STUDENT_A, Role.STUDENT, TENANT_A, MEMBER_STUDENT_A); }

    private String superAdmin() { return token(USER_ADMIN_A, Role.SUPER_ADMIN, null, null); }

    private static MockHttpServletRequestBuilder on(String host, MockHttpServletRequestBuilder b) {
        return b.header("Host", host);
    }

    private long scalar(String sql) throws SQLException {
        try (Connection c = TestPostgres.superuser(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---- tenant resolution ----------------------------------------------------------------------------------------

    @Test
    void tenantConfigIsResolvedFromTheSubdomain() throws Exception {
        mvc.perform(on(HOST_A, get("/api/v1/tenant-config")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("gec-a"))
                .andExpect(jsonPath("$.name").value("GEC A"));
        mvc.perform(on(HOST_B, get("/api/v1/tenant-config")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("gec-b"));
    }

    @Test
    void unknownSubdomainIs404AndApexIs400() throws Exception {
        mvc.perform(on("nope.basera.test", get("/api/v1/tenant-config")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
        mvc.perform(on("basera.test", get("/api/v1/tenant-config")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void xTenantIdHeaderIsIgnoredWhenDisabled() throws Exception {
        mvc.perform(on("basera.test", get("/api/v1/tenant-config")).header("X-Tenant-ID", TENANT_A.toString()))
                .andExpect(status().isBadRequest());
    }

    // ---- isolation through the full stack ---------------------------------------------------------------------------

    @Test
    void adminOfTenantAListsOnlyTenantAMembers() throws Exception {
        mvc.perform(on(HOST_A, get("/api/v1/admin/members")).header("Authorization", adminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[*].displayName", hasItem("Student A")))
                .andExpect(jsonPath("$.items[*].displayName", not(hasItem("Student B"))));
    }

    @Test
    void aTokenIsRejectedOnAnotherTenantsHost() throws Exception {
        mvc.perform(on(HOST_B, get("/api/v1/admin/members")).header("Authorization", adminA()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
    }

    @Test
    void adminCannotTouchAnotherTenantsMember() throws Exception {
        mvc.perform(on(HOST_A, post("/api/v1/admin/members/" + MEMBER_STUDENT_B + "/grant-owner"))
                        .header("Authorization", adminA()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        assertThat(scalar("SELECT count(*) FROM membership WHERE id = '" + MEMBER_STUDENT_B + "' AND role = 'OWNER'")).isZero();
    }

    // ---- roles ----------------------------------------------------------------------------------------------------

    @Test
    void studentCannotUseAdminEndpoints() throws Exception {
        mvc.perform(on(HOST_A, get("/api/v1/admin/members")).header("Authorization", studentA()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unauthenticatedRequestsGetAJson401() throws Exception {
        mvc.perform(on(HOST_A, get("/api/v1/me")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anOauthStateTokenCannotBeUsedAsAnAccessToken() throws Exception {
        String state = "Bearer " + jwt.issueStateToken("gec-a", "nonce-123");
        mvc.perform(on(HOST_A, get("/api/v1/me")).header("Authorization", state))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheCallersOwnMembership() throws Exception {
        mvc.perform(on(HOST_A, get("/api/v1/me")).header("Authorization", studentA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.displayName").value("Student A"))
                .andExpect(jsonPath("$.email").value("student.a@example.in"));
    }

    @Test
    void grantingOwnerWorksAndIsAudited() throws Exception {
        mvc.perform(on(HOST_A, post("/api/v1/admin/members/" + MEMBER_STUDENT_A + "/grant-owner"))
                        .header("Authorization", adminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        assertThat(scalar("SELECT count(*) FROM audit_log WHERE action = 'OWNER_GRANTED' AND tenant_id = '" + TENANT_A + "'"))
                .isEqualTo(1);
        // granting again is a conflict, not a silent no-op
        mvc.perform(on(HOST_A, post("/api/v1/admin/members/" + MEMBER_STUDENT_A + "/grant-owner"))
                        .header("Authorization", adminA()))
                .andExpect(status().isConflict());
    }

    // ---- platform (SUPER_ADMIN) -------------------------------------------------------------------------------------

    @Test
    void superAdminOnboardsACollegeAndGetsTheFirstAdminInvitation() throws Exception {
        String body = """
                {"name":"New College","slug":"new-college","cityId":"%s","campusLat":23.17,"campusLng":79.93,
                 "emailDomain":"newcollege.ac.in","joinPolicy":"DOMAIN_ONLY",
                 "branding":{"primaryColor":"#1d4ed8"},"adminEmail":"Principal@NewCollege.ac.in"}""".formatted(CITY_JABALPUR);
        mvc.perform(on(HOST_PLATFORM, post("/api/v1/platform/tenants")).header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.slug").value("new-college"))
                .andExpect(jsonPath("$.adminInvitation.email").value("principal@newcollege.ac.in"))
                .andExpect(jsonPath("$.adminInvitation.role").value("TENANT_ADMIN"));
        assertThat(scalar("SELECT count(*) FROM invitation WHERE role = 'TENANT_ADMIN' AND email = 'principal@newcollege.ac.in'"))
                .isEqualTo(1);
        mvc.perform(on("new-college.basera.test", get("/api/v1/tenant-config")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinPolicy").value("DOMAIN_ONLY"));
    }

    @Test
    void reservedSlugsAndBadBrandingAreRejected() throws Exception {
        String reserved = """
                {"name":"X","slug":"admin","cityId":"%s","campusLat":1,"campusLng":1,"adminEmail":"a@b.in"}""".formatted(CITY_JABALPUR);
        mvc.perform(on(HOST_PLATFORM, post("/api/v1/platform/tenants")).header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(reserved))
                .andExpect(status().isBadRequest());
        String badColor = """
                {"name":"X","slug":"good-slug","cityId":"%s","campusLat":1,"campusLng":1,
                 "branding":{"primaryColor":"red; background:url(x)"},"adminEmail":"a@b.in"}""".formatted(CITY_JABALPUR);
        mvc.perform(on(HOST_PLATFORM, post("/api/v1/platform/tenants")).header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(badColor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BRANDING"));
    }

    @Test
    void tenantTokensCannotReachPlatformEndpoints() throws Exception {
        mvc.perform(on(HOST_PLATFORM, get("/api/v1/platform/cities")).header("Authorization", adminA()))
                .andExpect(status().isForbidden());
        mvc.perform(on(HOST_A, get("/api/v1/platform/cities")).header("Authorization", superAdmin()))
                .andExpect(status().isNotFound());   // the platform API does not exist on college hosts
    }

    @Test
    void devLoginDoesNotExistOutsideTheDevProfile() throws Exception {
        mvc.perform(on(HOST_A, post("/api/v1/dev/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@y.in\",\"name\":\"X\",\"role\":\"STUDENT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingCitiesWorksForSuperAdmin() throws Exception {
        mvc.perform(on(HOST_PLATFORM, get("/api/v1/platform/cities")).header("Authorization", superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
