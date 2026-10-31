package app.basera.common.db;

import app.basera.common.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Thin SQL helper for the feature modules. It runs on the RLS-subject pool and joins the surrounding tenant-scoped
 * transaction, so every statement is automatically confined to the current tenant by PostgreSQL.
 * Results are plain maps whose keys are the (quoted, camelCase) column aliases; arrays, jsonb and timestamps are
 * converted to JSON-friendly values.
 */
@Component
public class Db {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public Db(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Parameter map builder that tolerates null values: p("a", 1, "b", null). */
    public static Map<String, Object> p(Object... keyValues) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    public List<Map<String, Object>> rows(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).query().listOfRows().stream().map(this::normalise).toList();
    }

    public Optional<Map<String, Object>> one(String sql, Map<String, Object> params) {
        List<Map<String, Object>> r = rows(sql, params);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public Map<String, Object> require(String sql, Map<String, Object> params, String code, String message) {
        return one(sql, params).orElseThrow(() -> ApiException.notFound(code, message));
    }

    public int exec(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).update();
    }

    /** Like exec, but throws 404 when no row was affected (typical for RLS-hidden or non-owned rows). */
    public void execOrNotFound(String sql, Map<String, Object> params, String code, String message) {
        if (exec(sql, params) == 0) {
            throw ApiException.notFound(code, message);
        }
    }

    public long count(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    public UUID id(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).query((rs, i) -> rs.getObject(1, UUID.class)).single();
    }

    private Map<String, Object> normalise(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(e.getKey(), convert(e.getValue()));
        }
        return out;
    }

    private Object convert(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Array a) {
            try {
                Object arr = a.getArray();
                return arr instanceof Object[] oa ? Arrays.stream(oa).map(this::convert).toList() : List.of();
            } catch (SQLException e) {
                return List.of();
            }
        }
        if (v.getClass().getName().equals("org.postgresql.util.PGobject")) {   // jsonb / json
            try {
                return mapper.readValue(v.toString(), Object.class);
            } catch (Exception e) {
                return null;
            }
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (v instanceof java.sql.Time t) {
            return t.toString();
        }
        return v;
    }
}
