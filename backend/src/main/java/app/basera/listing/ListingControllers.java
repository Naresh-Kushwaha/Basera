package app.basera.listing;

import app.basera.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

public class ListingControllers {

    private ListingControllers() {}

    public record AvailabilityRequest(@Pattern(regexp = "AVAILABLE|LIMITED|FULL") String status, LocalDate availableFrom) {}

    public record ActiveRequest(boolean active) {}

    public record MenuRequest(@Valid @NotEmpty List<ListingService.MenuItem> items) {}

    public record VisibilityRequest(@NotEmpty List<UUID> tenantIds) {}

    public record DecisionRequest(String reason) {}

    public record VerifyRequest(VisibilityAdminService.Checklist checklist, String notes, Integer validMonths) {}

    // ---------------------------------------------------------------- OWNER

    @RestController
    @RequestMapping("/api/v1/owner")
    @PreAuthorize("hasRole('OWNER')")
    public static class Owner {
        private final ListingService service;
        private final ListingQueryService query;

        public Owner(ListingService service, ListingQueryService query) {
            this.service = service;
            this.query = query;
        }

        @GetMapping("/listings")
        public List<Map<String, Object>> mine() {
            return service.mine();
        }

        @GetMapping("/tenants-in-city")
        public List<Map<String, Object>> tenants() {
            return service.tenantsInMyCity();
        }

        @PostMapping("/listings")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> create(@Valid @RequestBody ListingRequest request) {
            return Map.of("id", service.create(request));
        }

        @PutMapping("/listings/{id}")
        public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody ListingRequest request) {
            service.update(id, request);
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/listings/{id}")
        public Map<String, Object> get(@PathVariable UUID id) {
            return query.detail(id);
        }

        @PutMapping("/listings/{id}/availability")
        public ResponseEntity<Void> availability(@PathVariable UUID id, @Valid @RequestBody AvailabilityRequest r) {
            service.setAvailability(id, r.status(), r.availableFrom());
            return ResponseEntity.noContent().build();
        }

        @PutMapping("/listings/{id}/active")
        public ResponseEntity<Void> active(@PathVariable UUID id, @RequestBody ActiveRequest r) {
            service.setActive(id, r.active());
            return ResponseEntity.noContent().build();
        }

        @PutMapping("/listings/{id}/menu")
        public ResponseEntity<Void> menu(@PathVariable UUID id, @Valid @RequestBody MenuRequest r) {
            service.setMenu(id, r.items());
            return ResponseEntity.noContent().build();
        }

        @PutMapping("/listings/{id}/utilities")
        public ResponseEntity<Void> utilities(@PathVariable UUID id, @Valid @RequestBody ListingRequest.UtilityInput r) {
            service.saveUtilities(id, r);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/listings/{id}/visibility-requests")
        public Map<String, Object> visibility(@PathVariable UUID id, @Valid @RequestBody VisibilityRequest r) {
            return service.requestVisibility(id, r.tenantIds());
        }

        @PostMapping(value = "/listings/{id}/photos", consumes = "multipart/form-data")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> photo(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
            return service.addPhoto(id, file);
        }

        @DeleteMapping("/listings/{id}/photos/{photoId}")
        public ResponseEntity<Void> deletePhoto(@PathVariable UUID id, @PathVariable UUID photoId) {
            service.deletePhoto(id, photoId);
            return ResponseEntity.noContent().build();
        }
    }

    // ---------------------------------------------------------------- STUDENT / ANY MEMBER

    @RestController
    @RequestMapping("/api/v1/listings")
    public static class Public {
        private final ListingQueryService query;

        public Public(ListingQueryService query) {
            this.query = query;
        }

        @GetMapping
        public PageResponse<Map<String, Object>> search(
                @RequestParam(required = false) String group, @RequestParam(required = false) String kind,
                @RequestParam(required = false) Integer minRent, @RequestParam(required = false) Integer maxRent,
                @RequestParam(required = false) Integer maxDistanceM,
                @RequestParam(defaultValue = "false") boolean verifiedOnly,
                @RequestParam(required = false) String diet, @RequestParam(required = false) List<String> amenity,
                @RequestParam(defaultValue = "false") boolean availableOnly, @RequestParam(required = false) String q,
                @RequestParam(required = false) String sort,
                @RequestParam(required = false) Double minLat, @RequestParam(required = false) Double minLng,
                @RequestParam(required = false) Double maxLat, @RequestParam(required = false) Double maxLng,
                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
            return query.search(new ListingQueryService.Search(group, kind, minRent, maxRent, maxDistanceM, verifiedOnly,
                    diet, amenity, availableOnly, q, sort, minLat, minLng, maxLat, maxLng), page, size);
        }

        @GetMapping("/{id}")
        public Map<String, Object> get(@PathVariable UUID id) {
            return query.detail(id);
        }

        @GetMapping("/agreement-checklist")
        public List<Map<String, Object>> checklist() {
            return query.agreementChecklist();
        }
    }

    // ---------------------------------------------------------------- TENANT ADMIN

    @RestController
    @RequestMapping("/api/v1/admin")
    public static class Admin {
        private final VisibilityAdminService service;

        public Admin(VisibilityAdminService service) {
            this.service = service;
        }

        @GetMapping("/visibility-requests")
        public PageResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
            return service.list(status, page, size);
        }

        @PostMapping("/visibility/{id}/approve")
        public ResponseEntity<Void> approve(@PathVariable UUID id) {
            service.decide(id, "APPROVED", null);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/visibility/{id}/reject")
        public ResponseEntity<Void> reject(@PathVariable UUID id, @RequestBody DecisionRequest r) {
            service.decide(id, "REJECTED", r.reason());
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/visibility/{id}/suspend")
        public ResponseEntity<Void> suspend(@PathVariable UUID id, @RequestBody DecisionRequest r) {
            service.decide(id, "SUSPENDED", r.reason());
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/visibility/{id}/verification")
        public Map<String, Object> verify(@PathVariable UUID id, @RequestBody VerifyRequest r) {
            return service.verify(id, r.checklist(), r.notes(), r.validMonths());
        }
    }
}
