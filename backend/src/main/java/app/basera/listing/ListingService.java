package app.basera.listing;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.notification.NotificationService;
import app.basera.storage.ImageUploadProcessor;
import app.basera.storage.StorageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** OWNER-side listing management. Ownership is enforced twice: by RLS (owner_user_id = current user) and by 404s here. */
@Service
public class ListingService {

    static final Set<String> STAY_KINDS = Set.of("ROOM", "PG", "HOSTEL");
    static final Set<String> FOOD_KINDS = Set.of("MESS", "TIFFIN");
    private static final int MAX_PHOTOS = 8;

    private final Db db;
    private final Modules modules;
    private final NotificationService notifications;
    private final StorageService storage;
    private final ImageUploadProcessor images;

    public ListingService(Db db, Modules modules, NotificationService notifications, StorageService storage,
                          ImageUploadProcessor images) {
        this.db = db;
        this.modules = modules;
        this.notifications = notifications;
        this.storage = storage;
        this.images = images;
    }

    // ---- create / update ---------------------------------------------------------------------------------------

    @Transactional
    public UUID create(ListingRequest r) {
        modules.require(FOOD_KINDS.contains(r.kind()) ? "FOOD" : "STAY");
        validateDetails(r);
        UUID id = db.id("""
                INSERT INTO listing (owner_user_id, city_id, kind, title, description, address_line, locality, lat, lng, contact_phone)
                VALUES (:owner, (SELECT city_id FROM tenant WHERE id = app_current_tenant()), :kind, :title, :description,
                        :address, :locality, :lat, :lng, :phone)
                RETURNING id""",
                p("owner", Ctx.userId(), "kind", r.kind(), "title", TextSanitizer.cleanSingleLine(r.title()),
                        "description", TextSanitizer.clean(r.description()),
                        "address", TextSanitizer.cleanSingleLine(r.addressLine()),
                        "locality", TextSanitizer.cleanSingleLine(r.locality()),
                        "lat", r.lat().doubleValue(), "lng", r.lng().doubleValue(), "phone", r.contactPhone()));
        saveDetails(id, r);
        db.exec("""
                INSERT INTO listing_tenant_visibility (listing_id, tenant_id, city_id, owner_user_id)
                SELECT l.id, app_current_tenant(), l.city_id, l.owner_user_id FROM listing l WHERE l.id = :id""",
                p("id", id));
        notifications.notifyAdmins("LISTING_SUBMITTED", "New listing awaiting review", r.title(),
                Map.of("listingId", id.toString()));
        return id;
    }

    @Transactional
    public void update(UUID id, ListingRequest r) {
        Map<String, Object> existing = db.require("SELECT kind FROM listing WHERE id = :id AND owner_user_id = :me",
                p("id", id, "me", Ctx.userId()), "LISTING_NOT_FOUND", "Listing not found");
        if (!existing.get("kind").equals(r.kind())) {
            throw ApiException.badRequest("KIND_IMMUTABLE", "A listing's kind cannot be changed");
        }
        validateDetails(r);
        db.execOrNotFound("""
                UPDATE listing SET title = :title, description = :description, address_line = :address, locality = :locality,
                       lat = :lat, lng = :lng, contact_phone = :phone
                WHERE id = :id AND owner_user_id = :me""",
                p("id", id, "me", Ctx.userId(), "title", TextSanitizer.cleanSingleLine(r.title()),
                        "description", TextSanitizer.clean(r.description()),
                        "address", TextSanitizer.cleanSingleLine(r.addressLine()),
                        "locality", TextSanitizer.cleanSingleLine(r.locality()),
                        "lat", r.lat().doubleValue(), "lng", r.lng().doubleValue(), "phone", r.contactPhone()),
                "LISTING_NOT_FOUND", "Listing not found");
        saveDetails(id, r);
    }

    private void validateDetails(ListingRequest r) {
        if (STAY_KINDS.contains(r.kind()) && r.stay() == null) {
            throw ApiException.badRequest("STAY_DETAILS_REQUIRED", "Rent and rules are required for rooms, PGs and hostels");
        }
        if (FOOD_KINDS.contains(r.kind()) && r.food() == null) {
            throw ApiException.badRequest("FOOD_DETAILS_REQUIRED", "Monthly price and diet options are required for mess/tiffin");
        }
    }

    private void saveDetails(UUID id, ListingRequest r) {
        if (STAY_KINDS.contains(r.kind())) {
            ListingRequest.StayInput s = r.stay();
            db.exec("""
                    INSERT INTO stay_detail (listing_id, rent_monthly, deposit, room_type, gender_policy, curfew_time,
                        visitors_allowed, cooking_allowed, other_rules, amenities, agreement_provided)
                    VALUES (:id, :rent, COALESCE(:deposit, 0), :roomType, COALESCE(:gender, 'ANY'), :curfew,
                        :visitors, :cooking, :other, COALESCE(string_to_array(NULLIF(:amenities, ''), ','), '{}'),
                        COALESCE(:agreement, false))
                    ON CONFLICT (listing_id) DO UPDATE SET rent_monthly = EXCLUDED.rent_monthly, deposit = EXCLUDED.deposit,
                        room_type = EXCLUDED.room_type, gender_policy = EXCLUDED.gender_policy,
                        curfew_time = EXCLUDED.curfew_time, visitors_allowed = EXCLUDED.visitors_allowed,
                        cooking_allowed = EXCLUDED.cooking_allowed, other_rules = EXCLUDED.other_rules,
                        amenities = EXCLUDED.amenities, agreement_provided = EXCLUDED.agreement_provided""",
                    p("id", id, "rent", s.rentMonthly(), "deposit", s.deposit(), "roomType", s.roomType(),
                            "gender", s.genderPolicy(), "curfew", s.curfewTime(), "visitors", s.visitorsAllowed(),
                            "cooking", s.cookingAllowed(), "other", TextSanitizer.clean(s.otherRules()),
                            "amenities", csv(s.amenities()), "agreement", s.agreementProvided()));
        }
        if (FOOD_KINDS.contains(r.kind())) {
            ListingRequest.FoodInput f = r.food();
            List<String> meals = f.mealsIncluded() == null || f.mealsIncluded().isEmpty() ? List.of("LUNCH", "DINNER") : f.mealsIncluded();
            db.exec("""
                    INSERT INTO food_detail (listing_id, monthly_price, diet_options, meals_included, trial_meal_available, trial_meal_price)
                    VALUES (:id, :price, string_to_array(:diets, ','), string_to_array(:meals, ','), COALESCE(:trial, false), :trialPrice)
                    ON CONFLICT (listing_id) DO UPDATE SET monthly_price = EXCLUDED.monthly_price,
                        diet_options = EXCLUDED.diet_options, meals_included = EXCLUDED.meals_included,
                        trial_meal_available = EXCLUDED.trial_meal_available, trial_meal_price = EXCLUDED.trial_meal_price""",
                    p("id", id, "price", f.monthlyPrice(), "diets", String.join(",", f.dietOptions()),
                            "meals", String.join(",", meals), "trial", f.trialMealAvailable(), "trialPrice", f.trialMealPrice()));
        }
        if (r.utilities() != null) {
            saveUtilities(id, r.utilities());
        }
    }

    @Transactional
    public void saveUtilities(UUID id, ListingRequest.UtilityInput u) {
        requireOwned(id);
        db.exec("""
                INSERT INTO utility_info (listing_id, water_source, ro_available, power_backup, wifi_available, wifi_mbps, laundry, notes)
                VALUES (:id, :water, :ro, :power, :wifi, :mbps, :laundry, :notes)
                ON CONFLICT (listing_id) DO UPDATE SET water_source = EXCLUDED.water_source, ro_available = EXCLUDED.ro_available,
                    power_backup = EXCLUDED.power_backup, wifi_available = EXCLUDED.wifi_available, wifi_mbps = EXCLUDED.wifi_mbps,
                    laundry = EXCLUDED.laundry, notes = EXCLUDED.notes""",
                p("id", id, "water", u.waterSource(), "ro", u.roAvailable(), "power", u.powerBackup(),
                        "wifi", u.wifiAvailable(), "mbps", u.wifiMbps(), "laundry", u.laundry(),
                        "notes", TextSanitizer.clean(u.notes())));
    }

    // ---- owner reads --------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine() {
        List<Map<String, Object>> listings = db.rows("""
                SELECT l.id, l.kind, l.title, l.locality, l.active, l.availability_status AS "availabilityStatus",
                       l.availability_confirmed_at AS "availabilityConfirmedAt",
                       (l.availability_confirmed_at < now() - interval '30 days') AS "availabilityStale",
                       sd.rent_monthly AS "rentMonthly", fd.monthly_price AS "monthlyPrice",
                       (SELECT storage_key FROM listing_photo ph WHERE ph.listing_id = l.id ORDER BY sort_order, created_at LIMIT 1) AS cover
                FROM listing l
                LEFT JOIN stay_detail sd ON sd.listing_id = l.id
                LEFT JOIN food_detail fd ON fd.listing_id = l.id
                WHERE l.owner_user_id = :me ORDER BY l.created_at DESC""", p("me", Ctx.userId()));
        List<Map<String, Object>> vis = db.rows("""
                SELECT v.listing_id AS "listingId", v.tenant_id AS "tenantId", t.name AS "tenantName", v.status,
                       v.status_reason AS "statusReason", v.rating_avg AS "ratingAvg", v.rating_count AS "ratingCount"
                FROM listing_tenant_visibility v JOIN tenant t ON t.id = v.tenant_id
                WHERE v.owner_user_id = :me ORDER BY t.name""", p("me", Ctx.userId()));
        Map<Object, List<Map<String, Object>>> byListing = vis.stream().collect(Collectors.groupingBy(m -> m.get("listingId")));
        for (Map<String, Object> l : listings) {
            l.put("visibility", byListing.getOrDefault(l.get("id"), List.of()));
        }
        return listings;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tenantsInMyCity() {
        return db.rows("""
                SELECT t.id, t.name, t.slug FROM tenant t
                WHERE t.city_id = (SELECT city_id FROM tenant WHERE id = app_current_tenant())
                  AND t.active AND t.id <> app_current_tenant() ORDER BY t.name""", p());
    }

    // ---- small owner mutations ------------------------------------------------------------------------------------

    @Transactional
    public void setAvailability(UUID id, String status, java.time.LocalDate availableFrom) {
        db.execOrNotFound("""
                UPDATE listing SET availability_status = :s, available_from = :from, availability_confirmed_at = now()
                WHERE id = :id AND owner_user_id = :me""",
                p("s", status, "from", availableFrom, "id", id, "me", Ctx.userId()), "LISTING_NOT_FOUND", "Listing not found");
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        db.execOrNotFound("UPDATE listing SET active = :a WHERE id = :id AND owner_user_id = :me",
                p("a", active, "id", id, "me", Ctx.userId()), "LISTING_NOT_FOUND", "Listing not found");
    }

    public record MenuItem(@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(7) int day,
                           @jakarta.validation.constraints.Pattern(regexp = "BREAKFAST|LUNCH|DINNER") String meal,
                           @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String items) {}

    @Transactional
    public void setMenu(UUID id, List<MenuItem> menu) {
        Map<String, Object> l = requireOwned(id);
        if (!FOOD_KINDS.contains(l.get("kind"))) {
            throw ApiException.badRequest("NOT_A_FOOD_LISTING", "Only mess/tiffin listings have a menu");
        }
        db.exec("DELETE FROM mess_menu_item WHERE listing_id = :id", p("id", id));
        for (MenuItem m : menu) {
            db.exec("""
                    INSERT INTO mess_menu_item (listing_id, day_of_week, meal, items) VALUES (:id, :d, :meal, :items)
                    ON CONFLICT (listing_id, day_of_week, meal) DO UPDATE SET items = EXCLUDED.items""",
                    p("id", id, "d", m.day(), "meal", m.meal(), "items", TextSanitizer.cleanSingleLine(m.items())));
        }
        db.exec("UPDATE food_detail SET menu_updated_at = now() WHERE listing_id = :id", p("id", id));
    }

    /** Requests visibility at other colleges in the same city. Each college's admin decides separately. */
    @Transactional
    public Map<String, Object> requestVisibility(UUID id, List<UUID> tenantIds) {
        requireOwned(id);
        List<Map<String, Object>> eligible = db.rows("""
                SELECT t.id FROM tenant t
                WHERE t.id IN (:ids) AND t.active AND t.id <> app_current_tenant()
                  AND t.city_id = (SELECT city_id FROM listing WHERE id = :id)""", p("ids", tenantIds, "id", id));
        List<Object> requested = new ArrayList<>();
        for (Map<String, Object> t : eligible) {
            int n = db.exec("""
                    INSERT INTO listing_tenant_visibility (listing_id, tenant_id, city_id, owner_user_id)
                    SELECT l.id, :t, l.city_id, l.owner_user_id FROM listing l WHERE l.id = :id
                    ON CONFLICT (tenant_id, listing_id) DO NOTHING""", p("t", t.get("id"), "id", id));
            if (n > 0) {
                requested.add(t.get("id"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", requested);
        out.put("skipped", tenantIds.size() - requested.size());
        return out;
    }

    // ---- photos ---------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> addPhoto(UUID id, MultipartFile file) {
        requireOwned(id);
        long count = db.count("SELECT count(*) FROM listing_photo WHERE listing_id = :id", p("id", id));
        if (count >= MAX_PHOTOS) {
            throw ApiException.conflict("TOO_MANY_PHOTOS", "A listing can have at most " + MAX_PHOTOS + " photos");
        }
        byte[] jpeg = images.process(file);
        String key = UUID.randomUUID() + ".jpg";
        UUID photoId = db.id("""
                INSERT INTO listing_photo (listing_id, storage_key, content_type, sort_order)
                VALUES (:id, :key, 'image/jpeg', :sort) RETURNING id""", p("id", id, "key", key, "sort", (int) count));
        storage.put(key, jpeg);
        return Map.of("id", photoId, "key", key);
    }

    @Transactional
    public void deletePhoto(UUID listingId, UUID photoId) {
        Map<String, Object> photo = db.require("""
                SELECT p.storage_key AS key FROM listing_photo p JOIN listing l ON l.id = p.listing_id
                WHERE p.id = :pid AND p.listing_id = :id AND l.owner_user_id = :me""",
                p("pid", photoId, "id", listingId, "me", Ctx.userId()), "PHOTO_NOT_FOUND", "Photo not found");
        db.exec("DELETE FROM listing_photo WHERE id = :pid", p("pid", photoId));
        storage.delete((String) photo.get("key"));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private Map<String, Object> requireOwned(UUID id) {
        return db.require("SELECT id, kind FROM listing WHERE id = :id AND owner_user_id = :me",
                p("id", id, "me", Ctx.userId()), "LISTING_NOT_FOUND", "Listing not found");
    }

    private static String csv(List<String> values) {
        if (values == null) {
            return "";
        }
        return values.stream().map(v -> TextSanitizer.cleanSingleLine(v).replace(",", " ")).filter(v -> !v.isBlank())
                .collect(Collectors.joining(","));
    }
}
