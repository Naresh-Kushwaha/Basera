package app.basera.scam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

public class ScamControllers {

    private ScamControllers() {}

    public record ScamReportRequest(
            @NotBlank @Pattern(regexp = "FAKE_LISTING|ADVANCE_PAYMENT_DEMAND|MISLEADING_PHOTOS|PRICE_MISMATCH|OTHER") String reason,
            @Size(max = 1000) String details) {}

    public record ReasonRequest(@Size(max = 300) String reason) {}

    @RestController
    @RequestMapping("/api/v1")
    public static class Student {
        private final ScamService service;

        public Student(ScamService service) {
            this.service = service;
        }

        @PostMapping("/listings/{id}/scam-reports")
        @PreAuthorize("hasRole('STUDENT')")
        @ResponseStatus(HttpStatus.CREATED)
        public void report(@PathVariable UUID id, @Valid @RequestBody ScamReportRequest r) {
            service.report(id, r.reason(), r.details());
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin")
    public static class Admin {
        private final ScamService service;

        public Admin(ScamService service) {
            this.service = service;
        }

        @GetMapping("/scam-reports")
        public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
            return service.list(status);
        }

        @PostMapping("/scam-reports/{id}/confirm")
        public ResponseEntity<Void> confirm(@PathVariable UUID id) {
            service.resolve(id, true);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/scam-reports/{id}/dismiss")
        public ResponseEntity<Void> dismiss(@PathVariable UUID id) {
            service.resolve(id, false);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/owners/{userId}/suspend")
        public ResponseEntity<Void> suspendOwner(@PathVariable UUID userId, @Valid @RequestBody ReasonRequest r) {
            service.suspendOwner(userId, r.reason());
            return ResponseEntity.noContent().build();
        }
    }
}
