package app.basera.platform;

import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.common.web.PageResponse;
import app.basera.platform.dto.CityResponse;
import app.basera.platform.dto.CreateCityRequest;
import app.basera.platform.dto.CreateTenantRequest;
import app.basera.platform.dto.CreateTenantResponse;
import app.basera.platform.dto.InvitationSummary;
import app.basera.platform.dto.PlatformMeResponse;
import app.basera.platform.dto.UpdateTenantRequest;
import app.basera.security.CurrentUser;
import app.basera.tenancy.TenantInfo;
import app.basera.tenancy.TenantLookupService;
import app.basera.tenancy.TenantUrls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SUPER_ADMIN operations: cities, tenant onboarding, first TENANT_ADMIN invitation.
 * All of this runs on the platform pool (no tenant context exists for it) in platform transactions.
 */
@Service
public class PlatformService {

    static final Set<String> KNOWN_MODULES = Set.of("STAY", "FOOD", "UTILITIES", "REVIEWS", "ROOMMATES",
            "BUDGET", "GUIDE", "SAFETY", "NOTIFICATIONS", "SCAM_PROTECTION");
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern BRANDING_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,39}$");
    private static final int ADMIN_INVITE_DAYS = 30;

    private final PlatformJdbc db;
    private final TenantLookupService tenants;
    private final TenantUrls urls;
    private final ObjectMapper objectMapper;

    public PlatformService(PlatformJdbc db, TenantLookupService tenants, TenantUrls urls, ObjectMapper objectMapper) {
        this.db = db;
        this.tenants = tenants;
        this.urls = urls;
        this.objectMapper = objectMapper;
    }

    // ---- who am I (platform host) --------------------------------------------------------------------------

    @Transactional(transactionManager = "platformTx", readOnly = true)
    public PlatformMeResponse me() {
        UUID userId = CurrentUser.require().userId();
        return db.client().sql("SELECT id, full_name, email, photo_url FROM user_account WHERE id = :id")
                .param("id", userId)
                .query((rs, i) -> new PlatformMeResponse(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("email"), rs.getString("photo_url")))
                .optional().orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    // ---- cities ---------------------------------------------------------------------------------------------

    @Transactional(transactionManager = "platformTx", readOnly = true)
    public List<CityResponse> listCities() {
        return db.client().sql("SELECT id, name, state, country_code FROM city ORDER BY name")
                .query((rs, i) -> new CityResponse(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("state"), rs.getString("country_code").trim()))
                .list();
    }

    @Transactional(transactionManager = "platformTx")
    public CityResponse createCity(CreateCityRequest req) {
        String name = TextSanitizer.cleanSingleLine(req.name());
        String state = TextSanitizer.cleanSingleLine(req.state());
        String country = req.countryCode() == null ? "IN" : req.countryCode();
        try {
            UUID id = db.client().sql("""
                    INSERT INTO city (name, state, country_code, created_by) VALUES (:n, :s, :c, :by) RETURNING id
                    """)
                    .param("n", name).param("s", state).param("c", country).param("by", actor())
                    .query((rs, i) -> rs.getObject(1, UUID.class)).single();
            return new CityResponse(id, name, state, country);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CITY_EXISTS", "That city already exists");
        }
    }

    // ---- tenants --------------------------------------------------------------------------------------------

    @Transactional(transactionManager = "platformTx", readOnly = true)
    public PageResponse<TenantInfo> listTenants(int page, int size) {
        long total = db.client().sql("SELECT count(*) FROM tenant").query(Long.class).single();
        List<TenantInfo> items = tenants.loadPage(size, page * size);
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(transactionManager = "platformTx", readOnly = true)
    public TenantInfo getTenant(UUID id) {
        return tenants.loadFresh(id).orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
    }

    /** Creates the college, then pre-authorises its first TENANT_ADMIN. One transaction: both or neither. */
    @Transactional(transactionManager = "platformTx")
    public CreateTenantResponse createTenant(CreateTenantRequest req) {
        long cityCount = db.client().sql("SELECT count(*) FROM city WHERE id = :id")
                .param("id", req.cityId()).query(Long.class).single();
        if (cityCount == 0) {
            throw ApiException.notFound("CITY_NOT_FOUND", "City not found");
        }
        List<String> modules = req.enabledModules() == null ? List.copyOf(KNOWN_MODULES) : validateModules(req.enabledModules());
        List<String> languages = req.languages() == null || req.languages().isEmpty() ? List.of("en") : req.languages();
        String defaultLanguage = req.defaultLanguage() == null ? languages.get(0) : req.defaultLanguage();
        if (!languages.contains(defaultLanguage)) {
            throw ApiException.badRequest("INVALID_LANGUAGE", "defaultLanguage must be one of languages");
        }
        String joinPolicy = req.joinPolicy() == null ? "OPEN" : req.joinPolicy();
        String emailDomain = blankToNull(req.emailDomain());
        if ("DOMAIN_ONLY".equals(joinPolicy) && emailDomain == null) {
            throw ApiException.badRequest("EMAIL_DOMAIN_REQUIRED", "DOMAIN_ONLY colleges need an emailDomain");
        }

        UUID tenantId;
        try {
            tenantId = db.client().sql("""
                    INSERT INTO tenant (city_id, slug, name, campus_lat, campus_lng, email_domain, default_language,
                                        languages, branding, enabled_modules, join_policy, require_verified_student, created_by)
                    VALUES (:city, :slug, :name, :lat, :lng, :domain, :lang,
                            string_to_array(:languages, ','), CAST(:branding AS jsonb), string_to_array(:modules, ','),
                            :join, :verified, :by)
                    RETURNING id
                    """)
                    .param("city", req.cityId()).param("slug", req.slug())
                    .param("name", TextSanitizer.cleanSingleLine(req.name()))
                    .param("lat", req.campusLat().doubleValue()).param("lng", req.campusLng().doubleValue())
                    .param("domain", emailDomain).param("lang", defaultLanguage)
                    .param("languages", String.join(",", languages))
                    .param("branding", brandingJson(req.branding()))
                    .param("modules", String.join(",", modules))
                    .param("join", joinPolicy)
                    .param("verified", Boolean.TRUE.equals(req.requireVerifiedStudent()))
                    .param("by", actor())
                    .query((rs, i) -> rs.getObject(1, UUID.class)).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "That subdomain is already in use");
        } catch (DataIntegrityViolationException e) {
            throw ApiException.badRequest("INVALID_TENANT", "The tenant settings violate a constraint (reserved slug?)");
        }

        InvitationSummary invitation = inviteTenantAdmin(tenantId, req.slug(), req.adminEmail());
        audit(tenantId, "TENANT_CREATED", "tenant", tenantId, Map.of("slug", req.slug(), "adminEmail", invitation.email()));
        tenants.evictAll();
        return new CreateTenantResponse(getTenant(tenantId), invitation);
    }

    @Transactional(transactionManager = "platformTx")
    public TenantInfo updateTenant(UUID id, UpdateTenantRequest req) {
        TenantInfo current = getTenant(id);
        List<String> sets = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (req.name() != null) {
            sets.add("name = :name");
            params.put("name", TextSanitizer.cleanSingleLine(req.name()));
        }
        if (req.emailDomain() != null) {
            sets.add("email_domain = :domain");
            params.put("domain", blankToNull(req.emailDomain()));
        }
        if (req.languages() != null) {
            sets.add("languages = string_to_array(:languages, ',')");
            params.put("languages", String.join(",", req.languages().isEmpty() ? List.of("en") : req.languages()));
        }
        if (req.defaultLanguage() != null) {
            sets.add("default_language = :lang");
            params.put("lang", req.defaultLanguage());
        }
        if (req.branding() != null) {
            sets.add("branding = CAST(:branding AS jsonb)");
            params.put("branding", brandingJson(req.branding()));
        }
        if (req.joinPolicy() != null) {
            sets.add("join_policy = :join");
            params.put("join", req.joinPolicy());
        }
        if (req.requireVerifiedStudent() != null) {
            sets.add("require_verified_student = :verified");
            params.put("verified", req.requireVerifiedStudent());
        }
        if (req.active() != null) {
            sets.add("active = :active");
            params.put("active", req.active());
        }
        if (sets.isEmpty()) {
            throw ApiException.badRequest("NO_CHANGES", "Nothing to update");
        }
        sets.add("updated_by = :by");
        params.put("by", actor());
        params.put("id", id);
        try {
            db.client().sql("UPDATE tenant SET " + String.join(", ", sets) + " WHERE id = :id").params(params).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.badRequest("INVALID_TENANT", "The settings violate a constraint "
                    + "(e.g. DOMAIN_ONLY needs an emailDomain, defaultLanguage must be in languages)");
        }
        audit(id, "TENANT_UPDATED", "tenant", id, Map.of("fields", sets.size() - 1));
        tenants.evictAll();
        return getTenant(current.id());
    }

    @Transactional(transactionManager = "platformTx")
    public TenantInfo updateModules(UUID id, List<String> modules) {
        getTenant(id);
        List<String> valid = validateModules(modules);
        db.client().sql("UPDATE tenant SET enabled_modules = string_to_array(:modules, ','), updated_by = :by WHERE id = :id")
                .param("modules", String.join(",", valid)).param("by", actor()).param("id", id).update();
        audit(id, "TENANT_MODULES_UPDATED", "tenant", id, Map.of("modules", valid));
        tenants.evictAll();
        return getTenant(id);
    }

    @Transactional(transactionManager = "platformTx")
    public InvitationSummary inviteAdmin(UUID tenantId, String email) {
        TenantInfo t = getTenant(tenantId);
        InvitationSummary summary = inviteTenantAdmin(tenantId, t.slug(), email);
        audit(tenantId, "TENANT_ADMIN_INVITED", "invitation", null, Map.of("email", summary.email()));
        return summary;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private InvitationSummary inviteTenantAdmin(UUID tenantId, String slug, String adminEmail) {
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime expires = db.client().sql("""
                INSERT INTO invitation (tenant_id, email, role, expires_at, created_by)
                VALUES (:t, :email, 'TENANT_ADMIN', now() + make_interval(days => :days), :by)
                ON CONFLICT (tenant_id, email, role) WHERE consumed_at IS NULL
                DO UPDATE SET expires_at = EXCLUDED.expires_at
                RETURNING expires_at
                """)
                .param("t", tenantId).param("email", email).param("days", ADMIN_INVITE_DAYS).param("by", actor())
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class)).single();
        return new InvitationSummary(email, "TENANT_ADMIN", expires.toInstant(), urls.tenantBase(slug));
    }

    private void audit(UUID tenantId, String action, String targetType, UUID targetId, Map<String, Object> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        db.client().sql("""
                INSERT INTO audit_log (tenant_id, actor_user_id, action, target_type, target_id, details)
                VALUES (:t, :actor, :action, :tt, :tid, CAST(:details AS jsonb))
                """)
                .param("t", tenantId).param("actor", actor()).param("action", action)
                .param("tt", targetType).param("tid", targetId).param("details", json).update();
    }

    private List<String> validateModules(List<String> modules) {
        List<String> normalised = modules.stream().map(m -> m.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        for (String m : normalised) {
            if (!KNOWN_MODULES.contains(m)) {
                throw ApiException.badRequest("UNKNOWN_MODULE", "Unknown module: " + m);
            }
        }
        return normalised;
    }

    /**
     * Branding values end up in CSS variables on the frontend, so they are validated server-side:
     * *Color keys must be #rrggbb, logoUrl must be https, everything else is length-limited plain text.
     */
    private String brandingJson(Map<String, String> branding) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (branding != null) {
            for (Map.Entry<String, String> e : branding.entrySet()) {
                String key = e.getKey();
                String value = TextSanitizer.cleanSingleLine(e.getValue());
                if (!BRANDING_KEY.matcher(key).matches() || value == null || value.length() > 300) {
                    throw ApiException.badRequest("INVALID_BRANDING", "Invalid branding entry: " + key);
                }
                if (key.endsWith("Color") && !HEX_COLOR.matcher(value).matches()) {
                    throw ApiException.badRequest("INVALID_BRANDING", key + " must be a #rrggbb colour");
                }
                if (key.equals("logoUrl") && !value.startsWith("https://")) {
                    throw ApiException.badRequest("INVALID_BRANDING", "logoUrl must be an https URL");
                }
                clean.put(key, value);
            }
        }
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("INVALID_BRANDING", "Branding could not be serialised");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static UUID actor() {
        return CurrentUser.get().map(u -> u.userId()).orElse(null);
    }
}
