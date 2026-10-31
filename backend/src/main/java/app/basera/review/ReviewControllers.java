package app.basera.review;

import app.basera.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

public class ReviewControllers {

    private ReviewControllers() {}

    public record InteractionRequest(@NotBlank @Pattern(regexp = "STAY|MESS_SUBSCRIPTION|TRIAL_MEAL") String kind,
                                     LocalDate startDate) {}

    public record ReviewRequest(@NotNull UUID interactionId, @Min(1) @Max(5) int rating,
                                @Min(1) @Max(5) Integer hygieneRating, @NotBlank @Size(max = 4000) String body) {}

    public record ReviewUpdate(@Min(1) @Max(5) int rating, @Min(1) @Max(5) Integer hygieneRating,
                               @NotBlank @Size(max = 4000) String body) {}

    public record ReportRequest(@NotBlank @Size(max = 500) String reason) {}

    @RestController
    @RequestMapping("/api/v1")
    public static class Student {
        private final ReviewService service;

        public Student(ReviewService service) {
            this.service = service;
        }

        @PostMapping("/listings/{id}/interactions")
        @PreAuthorize("hasRole('STUDENT')")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> request(@PathVariable UUID id, @Valid @RequestBody InteractionRequest r) {
            return Map.of("id", service.requestInteraction(id, r.kind(), r.startDate()));
        }

        @GetMapping("/me/interactions")
        public List<Map<String, Object>> mine() {
            return service.mine();
        }

        @GetMapping("/listings/{id}/reviews")
        public PageResponse<Map<String, Object>> reviews(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
            return service.list(id, page, size);
        }

        @PostMapping("/listings/{id}/reviews")
        @PreAuthorize("hasRole('STUDENT')")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> create(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
            return Map.of("id", service.createReview(id, r.interactionId(), r.rating(), r.hygieneRating(), r.body()));
        }

        @PutMapping("/reviews/{id}")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody ReviewUpdate r) {
            service.updateReview(id, r.rating(), r.hygieneRating(), r.body());
            return ResponseEntity.noContent().build();
        }

        @DeleteMapping("/reviews/{id}")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<Void> delete(@PathVariable UUID id) {
            service.deleteReview(id);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/reviews/{id}/reports")
        @PreAuthorize("hasRole('STUDENT')")
        @ResponseStatus(HttpStatus.CREATED)
        public void report(@PathVariable UUID id, @Valid @RequestBody ReportRequest r) {
            service.report(id, r.reason());
        }
    }

    @RestController
    @RequestMapping("/api/v1/owner")
    @PreAuthorize("hasRole('OWNER')")
    public static class Owner {
        private final ReviewService service;

        public Owner(ReviewService service) {
            this.service = service;
        }

        @GetMapping("/interactions")
        public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
            return service.forOwner(status);
        }

        @PostMapping("/interactions/{id}/confirm")
        public ResponseEntity<Void> confirm(@PathVariable UUID id) {
            service.ownerDecide(id, true);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/interactions/{id}/reject")
        public ResponseEntity<Void> reject(@PathVariable UUID id) {
            service.ownerDecide(id, false);
            return ResponseEntity.noContent().build();
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin")
    public static class Admin {
        private final ReviewService service;

        public Admin(ReviewService service) {
            this.service = service;
        }

        @GetMapping("/interactions")
        public List<Map<String, Object>> interactions(@RequestParam(required = false) String status) {
            return service.forAdmin(status);
        }

        @PostMapping("/interactions/{id}/confirm")
        public ResponseEntity<Void> confirm(@PathVariable UUID id) {
            service.adminConfirm(id);
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/moderation/queue")
        public List<Map<String, Object>> queue() {
            return service.moderationQueue();
        }

        @PostMapping("/moderation/{reportId}/hide")
        public ResponseEntity<Void> hide(@PathVariable UUID reportId) {
            service.moderate(reportId, true);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/moderation/{reportId}/dismiss")
        public ResponseEntity<Void> dismiss(@PathVariable UUID reportId) {
            service.moderate(reportId, false);
            return ResponseEntity.noContent().build();
        }
    }
}
