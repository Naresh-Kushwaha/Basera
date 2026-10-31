package app.basera.tenancy;

import app.basera.platform.PlatformJdbc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves tenants BEFORE a tenant context exists (so it uses the platform pool) and caches the result
 * in-process for 60 seconds. After a tenant is edited, the local cache is evicted immediately; other
 * replicas converge within the TTL.
 */
@Service
public class TenantLookupService {

    private static final String SELECT = """
            SELECT t.id, t.slug, t.name, t.city_id, c.name AS city_name, t.campus_lat, t.campus_lng,
                   t.email_domain, t.default_language,
                   array_to_string(t.languages, ',')       AS languages,
                   t.branding::text                        AS branding,
                   array_to_string(t.enabled_modules, ',') AS enabled_modules,
                   t.join_policy, t.require_verified_student, t.active
            FROM tenant t JOIN city c ON c.id = t.city_id
            """;

    private final PlatformJdbc db;
    private final ObjectMapper objectMapper;
    private final Cache<String, Optional<TenantInfo>> bySlug =
            Caffeine.newBuilder().maximumSize(2_000).expireAfterWrite(Duration.ofSeconds(60)).build();
    private final Cache<UUID, Optional<TenantInfo>> byId =
            Caffeine.newBuilder().maximumSize(2_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    public TenantLookupService(PlatformJdbc db, ObjectMapper objectMapper) {
        this.db = db;
        this.objectMapper = objectMapper;
    }

    public Optional<TenantInfo> findBySlug(String slug) {
        return bySlug.get(slug, s -> db.client().sql(SELECT + " WHERE t.slug = :slug")
                .param("slug", s).query(this::map).optional());
    }

    public Optional<TenantInfo> findById(UUID id) {
        return byId.get(id, i -> db.client().sql(SELECT + " WHERE t.id = :id")
                .param("id", i).query(this::map).optional());
    }

    /** Always hits the database (used by the platform admin screens right after a write). */
    public Optional<TenantInfo> loadFresh(UUID id) {
        return db.client().sql(SELECT + " WHERE t.id = :id").param("id", id).query(this::map).optional();
    }

    public List<TenantInfo> loadPage(int limit, int offset) {
        return db.client().sql(SELECT + " ORDER BY t.name LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset).query(this::map).list();
    }

    public void evictAll() {
        bySlug.invalidateAll();
        byId.invalidateAll();
    }

    private TenantInfo map(ResultSet rs, int rowNum) throws SQLException {
        return new TenantInfo(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getObject("city_id", UUID.class),
                rs.getString("city_name"),
                rs.getDouble("campus_lat"),
                rs.getDouble("campus_lng"),
                rs.getString("email_domain"),
                rs.getString("default_language"),
                split(rs.getString("languages")),
                parseJson(rs.getString("branding")),
                split(rs.getString("enabled_modules")),
                rs.getString("join_policy"),
                rs.getBoolean("require_verified_student"),
                rs.getBoolean("active"));
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
