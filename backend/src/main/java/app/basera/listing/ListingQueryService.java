package app.basera.listing;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Student/admin/owner read side: search with PostGIS distance from THIS college's campus, and listing detail. */
@Service
public class ListingQueryService {

    /** A verification counts only if it is active, unexpired and newer than the listing's last material change. */
    public static final String VERIFIED_EXPR = """
            EXISTS (SELECT 1 FROM verification_record vr
                    WHERE vr.tenant_id = v.tenant_id AND vr.visibility_id = v.id AND vr.status = 'ACTIVE'
                      AND vr.expires_at > now() AND vr.verified_at >= l.material_changed_at)""";

    public record Search(String group, String kind, Integer minRent, Integer maxRent, Integer maxDistanceM,
                         boolean verifiedOnly, String diet, List<String> amenities, boolean availableOnly, String q,
                         String sort, Double minLat, Double minLng, Double maxLat, Double maxLng) {}

    private final Db db;

    public ListingQueryService(Db db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> search(Search s, int page, int size) {
        Pageable pg = Paging.of(page, size);
        StringBuilder where = new StringBuilder(" WHERE l.active");
        Map<String, Object> params = new HashMap<>();
        if ("STAY".equals(s.group())) {
            where.append(" AND l.kind IN ('ROOM','PG','HOSTEL')");
        } else if ("FOOD".equals(s.group())) {
            where.append(" AND l.kind IN ('MESS','TIFFIN')");
        }
        if (s.kind() != null) {
            where.append(" AND l.kind = :kind");
            params.put("kind", s.kind());
        }
        if (s.minRent() != null) {
            where.append(" AND COALESCE(sd.rent_monthly, fd.monthly_price) >= :minRent");
            params.put("minRent", s.minRent());
        }
        if (s.maxRent() != null) {
            where.append(" AND COALESCE(sd.rent_monthly, fd.monthly_price) <= :maxRent");
            params.put("maxRent", s.maxRent());
        }
        if (s.maxDistanceM() != null) {
            where.append(" AND ST_DWithin(l.geog, t.campus_geog, :maxDist)");
            params.put("maxDist", s.maxDistanceM().doubleValue());
        }
        if (s.verifiedOnly()) {
            where.append(" AND ").append(VERIFIED_EXPR);
        }
        if (s.diet() != null && !s.diet().isBlank()) {
            where.append(" AND :diet = ANY(fd.diet_options)");
            params.put("diet", s.diet());
        }
        List<String> amenities = s.amenities() == null ? List.of() : s.amenities();
        for (int i = 0; i < Math.min(amenities.size(), 10); i++) {
            where.append(" AND :am").append(i).append(" = ANY(sd.amenities)");
            params.put("am" + i, amenities.get(i));
        }
        if (s.availableOnly()) {
            where.append(" AND l.availability_status <> 'FULL'");
        }
        if (s.q() != null && !s.q().isBlank()) {
            where.append(" AND (l.title ILIKE :q OR l.locality ILIKE :q)");
            params.put("q", "%" + s.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (s.minLat() != null && s.maxLat() != null && s.minLng() != null && s.maxLng() != null) {
            where.append(" AND l.lat BETWEEN :minLat AND :maxLat AND l.lng BETWEEN :minLng AND :maxLng");
            params.put("minLat", s.minLat());
            params.put("maxLat", s.maxLat());
            params.put("minLng", s.minLng());
            params.put("maxLng", s.maxLng());
        }

        String from = """
                FROM listing l
                JOIN listing_tenant_visibility v ON v.listing_id = l.id AND v.tenant_id = app_current_tenant() AND v.status = 'APPROVED'
                JOIN tenant t ON t.id = v.tenant_id
                LEFT JOIN stay_detail sd ON sd.listing_id = l.id
                LEFT JOIN food_detail fd ON fd.listing_id = l.id""";

        String order = switch (s.sort() == null ? "distance" : s.sort()) {
            case "price" -> "COALESCE(sd.rent_monthly, fd.monthly_price) ASC NULLS LAST";
            case "rating" -> "v.rating_avg DESC NULLS LAST, v.rating_count DESC";
            case "newest" -> "l.created_at DESC";
            default -> "ST_Distance(l.geog, t.campus_geog) ASC";
        };

        long total = db.count("SELECT count(*) " + from + where, params);
        params.put("limit", pg.getPageSize());
        params.put("offset", (int) pg.getOffset());
        List<Map<String, Object>> items = db.rows("""
                SELECT l.id, l.kind, l.title, l.locality, l.lat, l.lng,
                       l.availability_status AS "availabilityStatus",
                       (l.availability_confirmed_at < now() - interval '30 days') AS "availabilityStale",
                       round(ST_Distance(l.geog, t.campus_geog))::int AS "distanceM",
                       v.rating_avg AS "ratingAvg", v.rating_count AS "ratingCount",
                       %s AS verified,
                       sd.rent_monthly AS "rentMonthly", sd.deposit, sd.room_type AS "roomType", sd.gender_policy AS "genderPolicy",
                       fd.monthly_price AS "monthlyPrice", fd.diet_options AS "dietOptions", fd.trial_meal_available AS "trialMealAvailable",
                       (SELECT storage_key FROM listing_photo ph WHERE ph.listing_id = l.id ORDER BY sort_order, created_at LIMIT 1) AS cover
                %s %s ORDER BY %s LIMIT :limit OFFSET :offset""".formatted(VERIFIED_EXPR, from, where, order), params);
        return PageResponse.of(items, pg.getPageNumber(), pg.getPageSize(), total);
    }

    /** Detail for the current college. Students only ever see APPROVED listings; admins and the owner see any status. */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID id) {
        boolean privileged = Ctx.isAdmin() || Ctx.me().role() == app.basera.security.Role.OWNER;
        Map<String, Object> d = db.require("""
                SELECT l.id, l.kind, l.title, l.description, l.address_line AS "addressLine", l.locality, l.lat, l.lng,
                       l.contact_phone AS "contactPhone", l.active,
                       l.availability_status AS "availabilityStatus", l.available_from AS "availableFrom",
                       l.availability_confirmed_at AS "availabilityConfirmedAt",
                       (l.availability_confirmed_at < now() - interval '30 days') AS "availabilityStale",
                       (l.owner_user_id = app_current_user()) AS "ownedByMe",
                       round(ST_Distance(l.geog, t.campus_geog))::int AS "distanceM",
                       v.id AS "visibilityId", v.status AS "visibilityStatus", v.status_reason AS "visibilityReason",
                       v.rating_avg AS "ratingAvg", v.rating_count AS "ratingCount",
                       CASE WHEN v.id IS NULL THEN false ELSE %s END AS verified
                FROM listing l
                JOIN tenant t ON t.id = app_current_tenant()
                LEFT JOIN listing_tenant_visibility v ON v.listing_id = l.id AND v.tenant_id = app_current_tenant()
                WHERE l.id = :id %s""".formatted(VERIFIED_EXPR,
                privileged ? "" : "AND v.status = 'APPROVED' AND l.active"),
                p("id", id), "LISTING_NOT_FOUND", "Listing not found");

        Map<String, Object> out = new java.util.LinkedHashMap<>(d);
        out.put("stay", db.one("""
                SELECT rent_monthly AS "rentMonthly", deposit, room_type AS "roomType", gender_policy AS "genderPolicy",
                       curfew_time AS "curfewTime", visitors_allowed AS "visitorsAllowed", cooking_allowed AS "cookingAllowed",
                       other_rules AS "otherRules", amenities, agreement_provided AS "agreementProvided"
                FROM stay_detail WHERE listing_id = :id""", p("id", id)).orElse(null));
        out.put("food", db.one("""
                SELECT monthly_price AS "monthlyPrice", diet_options AS "dietOptions", meals_included AS "mealsIncluded",
                       trial_meal_available AS "trialMealAvailable", trial_meal_price AS "trialMealPrice",
                       menu_updated_at AS "menuUpdatedAt"
                FROM food_detail WHERE listing_id = :id""", p("id", id)).orElse(null));
        out.put("utilities", db.one("""
                SELECT water_source AS "waterSource", ro_available AS "roAvailable", power_backup AS "powerBackup",
                       wifi_available AS "wifiAvailable", wifi_mbps AS "wifiMbps", laundry, notes
                FROM utility_info WHERE listing_id = :id""", p("id", id)).orElse(null));
        out.put("menu", db.rows("""
                SELECT day_of_week AS day, meal, items FROM mess_menu_item WHERE listing_id = :id
                ORDER BY day_of_week, CASE meal WHEN 'BREAKFAST' THEN 0 WHEN 'LUNCH' THEN 1 ELSE 2 END""", p("id", id)));
        out.put("photos", db.rows("""
                SELECT id, storage_key AS key FROM listing_photo WHERE listing_id = :id ORDER BY sort_order, created_at""",
                p("id", id)));
        if (d.get("visibilityId") != null) {
            out.put("verification", db.one("""
                    SELECT vr.verified_at AS "verifiedAt", vr.expires_at AS "expiresAt", t.name AS "verifiedBy"
                    FROM verification_record vr JOIN tenant t ON t.id = vr.tenant_id
                    WHERE vr.tenant_id = app_current_tenant() AND vr.visibility_id = :vid AND vr.status = 'ACTIVE'
                      AND vr.expires_at > now() AND vr.verified_at >= (SELECT material_changed_at FROM listing WHERE id = :id)
                    ORDER BY vr.verified_at DESC LIMIT 1""", p("vid", d.get("visibilityId"), "id", id)).orElse(null));
        }
        out.put("hygieneAvg", db.one("""
                SELECT round(avg(hygiene_rating), 1) AS avg FROM review
                WHERE tenant_id = app_current_tenant() AND listing_id = :id AND status = 'PUBLISHED' AND hygiene_rating IS NOT NULL""",
                p("id", id)).map(r -> r.get("avg")).orElse(null));
        return out;
    }

    /** Static, plain-language checklist shown before paying a deposit. Not legal advice. */
    public List<Map<String, Object>> agreementChecklist() {
        List<Map<String, Object>> items = new ArrayList<>();
        String[][] rows = {
                {"Written agreement", "Get a written rent agreement signed by both you and the owner before paying anything."},
                {"Names and IDs match", "The owner's name on the agreement should match the property papers or electricity bill."},
                {"Deposit terms", "Write down the deposit amount, when it is refundable, and any deductions."},
                {"Notice period", "Note the notice period for leaving and how rent changes are handled."},
                {"What is included", "List rent inclusions: electricity, water, Wi-Fi, food, laundry, furniture."},
                {"Receipts", "Ask for a receipt for every payment. Prefer bank transfer or UPI over cash."},
                {"Visit in person", "See the room yourself, or have a trusted person see it, before paying an advance."},
                {"House rules", "Confirm curfew, visitors and cooking rules in writing."}};
        for (String[] r : rows) {
            items.add(Map.of("title", r[0], "detail", r[1]));
        }
        return items;
    }
}
