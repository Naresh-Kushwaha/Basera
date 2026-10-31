package app.basera.platform;

import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SUPER_ADMIN curation of city-level content (stored once per city, shared read-only by every college in the city):
 * transport, health facilities, emergency numbers, safe-area notes, KYC guides. Table/column names come from a fixed
 * whitelist below, never from the request, so the dynamic SQL cannot be steered by input.
 */
@Service
public class CityContentService {

    private record Col(String json, String column, char type, boolean required) {}

    private record Spec(String table, String orderBy, List<Col> cols) {}

    private static final Map<String, Spec> SPECS = Map.of(
            "transport", new Spec("transport_route", "mode, name", List.of(
                    new Col("mode", "mode", 's', true), new Col("name", "name", 's', true),
                    new Col("fromPlace", "from_place", 's', true), new Col("toPlace", "to_place", 's', true),
                    new Col("fareMin", "fare_min", 'i', false), new Col("fareMax", "fare_max", 'i', false),
                    new Col("frequencyNote", "frequency_note", 's', false), new Col("notes", "notes", 's', false))),
            "health", new Spec("health_facility", "kind, name", List.of(
                    new Col("kind", "kind", 's', true), new Col("name", "name", 's', true),
                    new Col("address", "address", 's', false), new Col("phone", "phone", 's', false),
                    new Col("lat", "lat", 'd', false), new Col("lng", "lng", 'd', false),
                    new Col("open24x7", "open_24x7", 'b', false))),
            "emergency", new Spec("emergency_contact", "sort_order, label", List.of(
                    new Col("label", "label", 's', true), new Col("phone", "phone", 's', true),
                    new Col("description", "description", 's', false), new Col("sortOrder", "sort_order", 'i', false))),
            "safe-areas", new Spec("safe_area_note", "area_name", List.of(
                    new Col("areaName", "area_name", 's', true), new Col("note", "note", 's', true),
                    new Col("source", "source", 's', true))),
            "kyc", new Spec("bank_kyc_guide", "title", List.of(
                    new Col("title", "title", 's', true), new Col("bodyMd", "body_md", 's', true))));

    private final PlatformJdbc db;

    public CityContentService(PlatformJdbc db) {
        this.db = db;
    }

    @Transactional(transactionManager = "platformTx", readOnly = true)
    public List<Map<String, Object>> list(UUID cityId, String type) {
        Spec spec = spec(type);
        StringBuilder cols = new StringBuilder("id, last_reviewed_at");
        for (Col c : spec.cols()) {
            cols.append(", ").append(c.column()).append(" AS \"").append(c.json()).append("\"");
        }
        List<Map<String, Object>> rows = db.client().sql("SELECT " + cols + " FROM " + spec.table() + " WHERE city_id = :c ORDER BY " + spec.orderBy())
                .param("c", cityId).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k.equals("last_reviewed_at") ? "lastReviewedAt" : k, v instanceof Timestamp t ? t.toInstant() : v));
            out.add(m);
        }
        return out;
    }

    @Transactional(transactionManager = "platformTx")
    public UUID create(UUID cityId, String type, Map<String, Object> body) {
        Spec spec = spec(type);
        requireCity(cityId);
        Map<String, Object> values = coerce(spec, body);
        StringBuilder names = new StringBuilder("city_id");
        StringBuilder params = new StringBuilder(":city");
        for (Col c : spec.cols()) {
            names.append(", ").append(c.column());
            params.append(", :").append(c.json());
        }
        values.put("city", cityId);
        try {
            return db.client().sql("INSERT INTO " + spec.table() + " (" + names + ") VALUES (" + params + ") RETURNING id")
                    .params(values).query((rs, i) -> rs.getObject(1, UUID.class)).single();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.badRequest("INVALID_CONTENT", "The values are not acceptable (check allowed kinds/modes)");
        }
    }

    @Transactional(transactionManager = "platformTx")
    public void update(UUID cityId, String type, UUID id, Map<String, Object> body) {
        Spec spec = spec(type);
        Map<String, Object> values = coerce(spec, body);
        StringBuilder sets = new StringBuilder("last_reviewed_at = now()");
        for (Col c : spec.cols()) {
            sets.append(", ").append(c.column()).append(" = :").append(c.json());
        }
        values.put("id", id);
        values.put("city", cityId);
        int n;
        try {
            n = db.client().sql("UPDATE " + spec.table() + " SET " + sets + " WHERE id = :id AND city_id = :city").params(values).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.badRequest("INVALID_CONTENT", "The values are not acceptable (check allowed kinds/modes)");
        }
        if (n == 0) {
            throw ApiException.notFound("CONTENT_NOT_FOUND", "Item not found");
        }
    }

    @Transactional(transactionManager = "platformTx")
    public void delete(UUID cityId, String type, UUID id) {
        Spec spec = spec(type);
        int n = db.client().sql("DELETE FROM " + spec.table() + " WHERE id = :id AND city_id = :city")
                .param("id", id).param("city", cityId).update();
        if (n == 0) {
            throw ApiException.notFound("CONTENT_NOT_FOUND", "Item not found");
        }
    }

    private Spec spec(String type) {
        Spec s = SPECS.get(type);
        if (s == null) {
            throw ApiException.notFound("UNKNOWN_CONTENT_TYPE", "Unknown content type");
        }
        return s;
    }

    private void requireCity(UUID cityId) {
        long n = db.client().sql("SELECT count(*) FROM city WHERE id = :id").param("id", cityId).query(Long.class).single();
        if (n == 0) {
            throw ApiException.notFound("CITY_NOT_FOUND", "City not found");
        }
    }

    private Map<String, Object> coerce(Spec spec, Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        for (Col c : spec.cols()) {
            Object v = body.get(c.json());
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (c.required()) {
                    throw ApiException.badRequest("MISSING_FIELD", c.json() + " is required");
                }
                out.put(c.json(), c.type() == 'b' ? Boolean.FALSE : c.json().equals("sortOrder") ? Integer.valueOf(0) : null);
                continue;
            }
            try {
                out.put(c.json(), switch (c.type()) {
                    case 'i' -> v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().trim());
                    case 'd' -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString().trim());
                    case 'b' -> v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
                    default -> TextSanitizer.truncate(TextSanitizer.clean(v.toString()), 8000);
                });
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("INVALID_FIELD", c.json() + " has an invalid value");
            }
        }
        return out;
    }
}
