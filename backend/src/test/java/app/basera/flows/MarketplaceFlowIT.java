package app.basera.flows;

import static app.basera.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.basera.security.JwtService;
import app.basera.security.Role;
import app.basera.support.TestData;
import app.basera.support.TestPostgres;
import app.basera.tenancy.TenantLookupService;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
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

/** The whole marketplace loop through the real stack: owner lists -> admin approves/verifies -> student finds, requests, is gated. */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceFlowIT {

    private static final String HOST_A = "gec-a.basera.test";
    private static final String HOST_B = "gec-b.basera.test";

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
        r.add("basera.storage.local-dir", () -> System.getProperty("java.io.tmpdir") + "/basera-it-media");
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired TenantLookupService tenants;

    @BeforeEach
    void seed() throws SQLException {
        TestData.reset();
        tenants.evictAll();
    }

    private String bearer(UUID user, Role role, UUID tenant, UUID member) {
        return "Bearer " + jwt.issueAccessToken(user, role, tenant, member);
    }

    private String owner() { return bearer(USER_OWNER, Role.OWNER, TENANT_A, MEMBER_OWNER_A); }

    private String admin() { return bearer(USER_ADMIN_A, Role.TENANT_ADMIN, TENANT_A, MEMBER_ADMIN_A); }

    private String student() { return bearer(USER_STUDENT_A, Role.STUDENT, TENANT_A, MEMBER_STUDENT_A); }

    private static MockHttpServletRequestBuilder on(String host, String auth, MockHttpServletRequestBuilder b) {
        return b.header("Host", host).header("Authorization", auth);
    }

    private static final String NEW_PG = """
            {"kind":"PG","title":"Sunrise PG","addressLine":"5 Civil Lines","locality":"Civil Lines","lat":23.2160,"lng":79.9505,
             "contactPhone":"+91 98765 43210",
             "stay":{"rentMonthly":4200,"deposit":4200,"roomType":"DOUBLE","genderPolicy":"ANY","curfewTime":"22:30",
                     "visitorsAllowed":true,"cookingAllowed":false,"amenities":["WIFI","LAUNDRY"],"agreementProvided":true},
             "utilities":{"waterSource":"MUNICIPAL","roAvailable":true,"powerBackup":"INVERTER","wifiAvailable":true,"wifiMbps":50}}""";

    @Test
    void ownerListsAdminApprovesAndVerifiesStudentFindsIt() throws Exception {
        String created = mvc.perform(on(HOST_A, owner(), post("/api/v1/owner/listings")).contentType(MediaType.APPLICATION_JSON).content(NEW_PG))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.id");

        // PENDING: invisible to students
        mvc.perform(on(HOST_A, student(), get("/api/v1/listings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title", not(hasItem("Sunrise PG"))));
        mvc.perform(on(HOST_A, student(), get("/api/v1/listings/" + listingId))).andExpect(status().isNotFound());

        // admin approves
        String pending = mvc.perform(on(HOST_A, admin(), get("/api/v1/admin/visibility-requests?status=PENDING")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String visId = JsonPath.read(pending, "$.items[?(@.title=='Sunrise PG')].id[0]");
        mvc.perform(on(HOST_A, admin(), post("/api/v1/admin/visibility/" + visId + "/approve"))).andExpect(status().isNoContent());

        mvc.perform(on(HOST_A, student(), get("/api/v1/listings?group=STAY&sort=price")))
                .andExpect(jsonPath("$.items[*].title", hasItem("Sunrise PG")))
                .andExpect(jsonPath("$.items[?(@.title=='Sunrise PG')].verified[0]").value(false));

        // verification needs the full checklist
        mvc.perform(on(HOST_A, admin(), post("/api/v1/admin/visibility/" + visId + "/verification"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklist\":{\"identityChecked\":true,\"ownershipChecked\":true,\"siteVisitDone\":false,\"termsConfirmed\":true}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHECKLIST_INCOMPLETE"));
        mvc.perform(on(HOST_A, admin(), post("/api/v1/admin/visibility/" + visId + "/verification"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklist\":{\"identityChecked\":true,\"ownershipChecked\":true,\"siteVisitDone\":true,\"termsConfirmed\":true},\"validMonths\":6}"))
                .andExpect(status().isOk());
        mvc.perform(on(HOST_A, student(), get("/api/v1/listings?verifiedOnly=true")))
                .andExpect(jsonPath("$.items[*].title", hasItem("Sunrise PG")));

        // the other college can never see it
        mvc.perform(on(HOST_B, bearer(USER_STUDENT_B, Role.STUDENT, TENANT_B, MEMBER_STUDENT_B), get("/api/v1/listings/" + listingId)))
                .andExpect(status().isNotFound());
        mvc.perform(on(HOST_B, bearer(USER_STUDENT_B, Role.STUDENT, TENANT_B, MEMBER_STUDENT_B), get("/api/v1/listings")))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void reviewsAreGatedByConfirmedInteractionAndElapsedTime() throws Exception {
        // seeded listing is already APPROVED for college A
        String id = LISTING_1.toString();
        // review without an interaction is refused
        mvc.perform(on(HOST_A, student(), post("/api/v1/listings/" + id + "/reviews")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionId\":\"" + UUID.randomUUID() + "\",\"rating\":5,\"body\":\"great\"}"))
                .andExpect(status().isNotFound());
        // request -> owner confirms -> too early (needs 14 days)
        String r = mvc.perform(on(HOST_A, student(), post("/api/v1/listings/" + id + "/interactions")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"STAY\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String interaction = JsonPath.read(r, "$.id");
        mvc.perform(on(HOST_A, owner(), post("/api/v1/owner/interactions/" + interaction + "/confirm"))).andExpect(status().isNoContent());
        mvc.perform(on(HOST_A, student(), post("/api/v1/listings/" + id + "/reviews")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionId\":\"" + interaction + "\",\"rating\":5,\"body\":\"great\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_EARLY"));
        // a duplicate open request is refused
        mvc.perform(on(HOST_A, student(), post("/api/v1/listings/" + id + "/interactions")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"STAY\"}")).andExpect(status().isConflict());
        // an owner cannot confirm someone else's listing interactions (none pending now): 404
        mvc.perform(on(HOST_A, owner(), post("/api/v1/owner/interactions/" + interaction + "/confirm"))).andExpect(status().isNotFound());
    }

    @Test
    void studentsCannotUseOwnerOrAdminEndpoints() throws Exception {
        mvc.perform(on(HOST_A, student(), post("/api/v1/owner/listings")).contentType(MediaType.APPLICATION_JSON).content(NEW_PG))
                .andExpect(status().isForbidden());
        mvc.perform(on(HOST_A, student(), get("/api/v1/admin/visibility-requests"))).andExpect(status().isForbidden());
        mvc.perform(on(HOST_A, owner(), get("/api/v1/admin/analytics/overview"))).andExpect(status().isForbidden());
    }

    @Test
    void guideSafetyBudgetAndMessagingBasics() throws Exception {
        mvc.perform(on(HOST_A, student(), get("/api/v1/guide/emergency"))).andExpect(status().isOk());
        mvc.perform(on(HOST_A, student(), post("/api/v1/budget/expenses")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":250.5,\"category\":\"FOOD\",\"spentOn\":\"" + java.time.LocalDate.now() + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(on(HOST_A, student(), get("/api/v1/budget/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(250.5));
        mvc.perform(on(HOST_A, student(), post("/api/v1/safety/contacts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mom\",\"phone\":\"+91 98765 43210\"}")).andExpect(status().isCreated());
        mvc.perform(on(HOST_A, student(), post("/api/v1/safety/sos")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":23.2,\"lng\":79.9,\"alertCollegeAdmin\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.collegeAlerted").value(false));
        // listing inquiry -> owner sees the conversation, a third party (admin) cannot read it
        String c = mvc.perform(on(HOST_A, student(), post("/api/v1/conversations")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LISTING_INQUIRY\",\"listingId\":\"" + LISTING_1 + "\",\"message\":\"Is it available?\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String convo = JsonPath.read(c, "$.id");
        mvc.perform(on(HOST_A, owner(), get("/api/v1/conversations/" + convo + "/messages"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body").value("Is it available?"));
        mvc.perform(on(HOST_A, admin(), get("/api/v1/conversations/" + convo + "/messages"))).andExpect(status().isNotFound());
        assertThat(convo).isNotBlank();
    }
}
