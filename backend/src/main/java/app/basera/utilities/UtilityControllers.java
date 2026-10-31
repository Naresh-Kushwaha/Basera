package app.basera.utilities;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

public class UtilityControllers {

    private UtilityControllers() {}

    public record StatusRequest(String status) {}

    @RestController
    @RequestMapping("/api/v1")
    public static class Student {
        private final UtilityReportService service;

        public Student(UtilityReportService service) {
            this.service = service;
        }

        @PostMapping("/utility-reports")
        @PreAuthorize("hasRole('STUDENT')")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> create(@RequestBody UtilityReportService.CreateRequest r) {
            return Map.of("id", service.create(r));
        }

        @GetMapping("/utility-reports/mine")
        public List<Map<String, Object>> mine() {
            return service.mine();
        }

        @GetMapping("/listings/{id}/utility-reports")
        public List<Map<String, Object>> forListing(@PathVariable UUID id) {
            return service.forListing(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/owner/utility-reports")
    @PreAuthorize("hasRole('OWNER')")
    public static class Owner {
        private final UtilityReportService service;

        public Owner(UtilityReportService service) {
            this.service = service;
        }

        @GetMapping
        public List<Map<String, Object>> list() {
            return service.forOwner();
        }

        @PatchMapping("/{id}")
        public ResponseEntity<Void> status(@PathVariable UUID id, @RequestBody StatusRequest r) {
            service.setStatus(id, r.status(), true);
            return ResponseEntity.noContent().build();
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/utility-reports")
    public static class Admin {
        private final UtilityReportService service;

        public Admin(UtilityReportService service) {
            this.service = service;
        }

        @GetMapping
        public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
            return service.forAdmin(status);
        }

        @PatchMapping("/{id}")
        public ResponseEntity<Void> status(@PathVariable UUID id, @RequestBody StatusRequest r) {
            service.setStatus(id, r.status(), false);
            return ResponseEntity.noContent().build();
        }
    }
}
