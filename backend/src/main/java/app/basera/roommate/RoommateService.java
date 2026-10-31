package app.basera.roommate;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.db.StudentGate;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Roommate posts and a transparent, explainable matching score (no black box: the reasons are returned with the score). */
@Service
public class RoommateService {

    private static final int MAX_ACTIVE_POSTS = 2;
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "diet", Set.of("any", "veg", "nonveg", "jain"),
            "sleep", Set.of("early", "flexible", "late"),
            "smoking", Set.of("no", "yes"),
            "genderPref", Set.of("any", "same"),
            "cleanliness", Set.of("1", "2", "3", "4", "5"));

    public record PostRequest(String lookingFor, String title, String body, Integer budgetMin, Integer budgetMax,
                              LocalDate moveInDate, Map<String, Object> preferences) {}

    private final Db db;
    private final Modules modules;
    private final StudentGate gate;
    private final ObjectMapper mapper;

    public RoommateService(Db db, Modules modules, StudentGate gate, ObjectMapper mapper) {
        this.db = db;
        this.modules = modules;
        this.gate = gate;
        this.mapper = mapper;
    }

    @Transactional
    public UUID create(PostRequest r) {
        modules.require("ROOMMATES");
        gate.requireVerifiedIfConfigured();
        validate(r);
        long active = db.count("SELECT count(*) FROM roommate_post WHERE author_membership_id = :me AND status = 'ACTIVE'",
                p("me", Ctx.membershipId()));
        if (active >= MAX_ACTIVE_POSTS) {
            throw ApiException.conflict("TOO_MANY_POSTS", "You can have at most " + MAX_ACTIVE_POSTS + " active posts. Close one first.");
        }
        return db.id("""
                INSERT INTO roommate_post (tenant_id, author_membership_id, looking_for, title, body, budget_min, budget_max, move_in_date, preferences)
                VALUES (app_current_tenant(), :me, :lf, :title, :body, :min, :max, :move, CAST(:prefs AS jsonb)) RETURNING id""",
                params(r));
    }

    @Transactional
    public void update(UUID id, PostRequest r) {
        validate(r);
        Map<String, Object> params = params(r);
        params.put("id", id);
        db.execOrNotFound("""
                UPDATE roommate_post SET looking_for = :lf, title = :title, body = :body, budget_min = :min, budget_max = :max,
                       move_in_date = :move, preferences = CAST(:prefs AS jsonb)
                WHERE id = :id AND author_membership_id = :me""", params, "POST_NOT_FOUND", "Post not found");
    }

    @Transactional
    public void close(UUID id) {
        db.execOrNotFound("UPDATE roommate_post SET status = 'CLOSED' WHERE id = :id AND author_membership_id = :me AND status = 'ACTIVE'",
                p("id", id, "me", Ctx.membershipId()), "POST_NOT_FOUND", "Post not found");
    }

    @Transactional
    public void delete(UUID id) {
        db.execOrNotFound("DELETE FROM roommate_post WHERE id = :id AND author_membership_id = :me",
                p("id", id, "me", Ctx.membershipId()), "POST_NOT_FOUND", "Post not found");
    }

    /** Admin moderation: hide a post without deleting it. */
    @Transactional
    public void hide(UUID id) {
        db.execOrNotFound("UPDATE roommate_post SET status = 'HIDDEN' WHERE id = :id",
                p("id", id), "POST_NOT_FOUND", "Post not found");
    }

    private static final String SELECT = """
            SELECT rp.id, rp.looking_for AS "lookingFor", rp.title, rp.body, rp.budget_min AS "budgetMin", rp.budget_max AS "budgetMax",
                   rp.move_in_date AS "moveInDate", rp.preferences, rp.status, rp.created_at AS "createdAt",
                   rp.author_membership_id AS "authorMembershipId", m.display_name AS "authorName", m.photo_url AS "authorPhoto",
                   (rp.author_membership_id = :me) AS mine
            FROM roommate_post rp JOIN membership m ON m.id = rp.author_membership_id AND m.tenant_id = rp.tenant_id""";

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(String lookingFor, Integer maxBudget, int page, int size) {
        Pageable pg = Paging.of(page, size);
        StringBuilder where = new StringBuilder(" WHERE rp.status = 'ACTIVE'");
        Map<String, Object> params = p("me", Ctx.membershipId());
        if (lookingFor != null && !lookingFor.isBlank()) {
            where.append(" AND rp.looking_for = :lf");
            params.put("lf", lookingFor);
        }
        if (maxBudget != null) {
            where.append(" AND (rp.budget_min IS NULL OR rp.budget_min <= :maxBudget)");
            params.put("maxBudget", maxBudget);
        }
        long total = db.count("SELECT count(*) FROM roommate_post rp" + where, params);
        params.put("limit", pg.getPageSize());
        params.put("offset", (int) pg.getOffset());
        List<Map<String, Object>> items = db.rows(SELECT + where + " ORDER BY rp.created_at DESC LIMIT :limit OFFSET :offset", params);
        return PageResponse.of(items, pg.getPageNumber(), pg.getPageSize(), total);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine() {
        return db.rows(SELECT + " WHERE rp.author_membership_id = :me ORDER BY rp.created_at DESC", p("me", Ctx.membershipId()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> matches() {
        List<Map<String, Object>> mine = db.rows(SELECT + " WHERE rp.author_membership_id = :me AND rp.status = 'ACTIVE' ORDER BY rp.created_at DESC LIMIT 1",
                p("me", Ctx.membershipId()));
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<String, Object> me = mine.get(0);
        List<Map<String, Object>> candidates = db.rows(SELECT + " WHERE rp.status = 'ACTIVE' AND rp.author_membership_id <> :me ORDER BY rp.created_at DESC LIMIT 200",
                p("me", Ctx.membershipId()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            Map<String, Object> scored = new LinkedHashMap<>(c);
            List<String> reasons = new ArrayList<>();
            scored.put("score", score(me, c, reasons));
            scored.put("reasons", reasons);
            out.add(scored);
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("score")).reversed());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    /** 0-100. Every component adds a human-readable reason so students can see WHY someone matches. */
    @SuppressWarnings("unchecked")
    static int score(Map<String, Object> a, Map<String, Object> b, List<String> reasons) {
        int score = 0;
        Integer aMin = (Integer) a.get("budgetMin"), aMax = (Integer) a.get("budgetMax");
        Integer bMin = (Integer) b.get("budgetMin"), bMax = (Integer) b.get("budgetMax");
        if ((aMax != null || aMin != null) && (bMax != null || bMin != null)) {
            int lo = Math.max(aMin == null ? 0 : aMin, bMin == null ? 0 : bMin);
            int hi = Math.min(aMax == null ? Integer.MAX_VALUE : aMax, bMax == null ? Integer.MAX_VALUE : bMax);
            if (lo <= hi) {
                score += 25;
                reasons.add("Budgets overlap");
            }
        }
        Map<String, Object> pa = a.get("preferences") instanceof Map<?, ?> m1 ? (Map<String, Object>) m1 : Map.of();
        Map<String, Object> pb = b.get("preferences") instanceof Map<?, ?> m2 ? (Map<String, Object>) m2 : Map.of();
        String diet1 = str(pa.get("diet")), diet2 = str(pb.get("diet"));
        if (diet1 != null && diet2 != null && (diet1.equals(diet2) || diet1.equals("any") || diet2.equals("any"))) {
            score += 15;
            reasons.add("Compatible food preferences");
        }
        String sleep1 = str(pa.get("sleep")), sleep2 = str(pb.get("sleep"));
        if (sleep1 != null && sleep2 != null && (sleep1.equals(sleep2) || sleep1.equals("flexible") || sleep2.equals("flexible"))) {
            score += 15;
            reasons.add("Similar sleep schedule");
        }
        String smoke1 = str(pa.get("smoking")), smoke2 = str(pb.get("smoking"));
        if (smoke1 != null && smoke2 != null) {
            if (smoke1.equals(smoke2)) {
                score += 20;
                reasons.add("Same smoking preference");
            } else {
                score -= 20;
                reasons.add("Different smoking preference");
            }
        }
        String c1 = str(pa.get("cleanliness")), c2 = str(pb.get("cleanliness"));
        if (c1 != null && c2 != null) {
            int diff = Math.abs(Integer.parseInt(c1) - Integer.parseInt(c2));
            score += Math.round(20 * (1 - diff / 4f));
            if (diff <= 1) {
                reasons.add("Similar cleanliness standards");
            }
        }
        Object ma = a.get("moveInDate"), mb = b.get("moveInDate");
        if (ma instanceof java.sql.Date da && mb instanceof java.sql.Date db2
                && Math.abs(ChronoUnit.DAYS.between(da.toLocalDate(), db2.toLocalDate())) <= 30) {
            score += 5;
            reasons.add("Moving in around the same time");
        }
        return Math.max(0, Math.min(100, score));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private void validate(PostRequest r) {
        if (r.lookingFor() == null || !Set.of("ROOM_WITH_ROOMMATE", "ROOMMATE_FOR_MY_PLACE").contains(r.lookingFor())) {
            throw ApiException.badRequest("INVALID_LOOKING_FOR", "lookingFor must be ROOM_WITH_ROOMMATE or ROOMMATE_FOR_MY_PLACE");
        }
        if (r.title() == null || r.title().isBlank() || r.title().length() > 120) {
            throw ApiException.badRequest("INVALID_TITLE", "A title of up to 120 characters is required");
        }
        if (r.body() != null && r.body().length() > 2000) {
            throw ApiException.badRequest("INVALID_BODY", "Description is too long");
        }
        if (r.budgetMin() != null && r.budgetMax() != null && r.budgetMax() < r.budgetMin()) {
            throw ApiException.badRequest("INVALID_BUDGET", "Maximum budget must be at least the minimum");
        }
        if (r.preferences() != null) {
            for (Map.Entry<String, Object> e : r.preferences().entrySet()) {
                Set<String> allowed = ALLOWED.get(e.getKey());
                if (allowed == null || e.getValue() == null || !allowed.contains(e.getValue().toString())) {
                    throw ApiException.badRequest("INVALID_PREFERENCE", "Unknown preference or value: " + e.getKey());
                }
            }
        }
    }

    private Map<String, Object> params(PostRequest r) {
        Map<String, Object> m = new HashMap<>();
        m.put("me", Ctx.membershipId());
        m.put("lf", r.lookingFor());
        m.put("title", TextSanitizer.cleanSingleLine(r.title()));
        m.put("body", TextSanitizer.clean(r.body()));
        m.put("min", r.budgetMin());
        m.put("max", r.budgetMax());
        m.put("move", r.moveInDate());
        try {
            Map<String, String> prefs = new LinkedHashMap<>();
            if (r.preferences() != null) {
                r.preferences().forEach((k, v) -> prefs.put(k, v.toString()));
            }
            m.put("prefs", mapper.writeValueAsString(prefs));
        } catch (JsonProcessingException e) {
            m.put("prefs", "{}");
        }
        return m;
    }
}
