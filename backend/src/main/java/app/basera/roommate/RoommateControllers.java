package app.basera.roommate;

import app.basera.common.web.PageResponse;
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

public class RoommateControllers {

    private RoommateControllers() {}

    @RestController
    @RequestMapping("/api/v1/roommate-posts")
    public static class Posts {
        private final RoommateService service;

        public Posts(RoommateService service) {
            this.service = service;
        }

        @GetMapping
        public PageResponse<Map<String, Object>> list(@RequestParam(required = false) String lookingFor,
                                                      @RequestParam(required = false) Integer maxBudget,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
            return service.list(lookingFor, maxBudget, page, size);
        }

        @GetMapping("/mine")
        public List<Map<String, Object>> mine() {
            return service.mine();
        }

        @GetMapping("/matches")
        public List<Map<String, Object>> matches() {
            return service.matches();
        }

        @PostMapping
        @PreAuthorize("hasRole('STUDENT')")
        @ResponseStatus(HttpStatus.CREATED)
        public Map<String, Object> create(@RequestBody RoommateService.PostRequest r) {
            return Map.of("id", service.create(r));
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody RoommateService.PostRequest r) {
            service.update(id, r);
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/{id}/close")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<Void> close(@PathVariable UUID id) {
            service.close(id);
            return ResponseEntity.noContent().build();
        }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<Void> delete(@PathVariable UUID id) {
            service.delete(id);
            return ResponseEntity.noContent().build();
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/roommate-posts")
    public static class Admin {
        private final RoommateService service;

        public Admin(RoommateService service) {
            this.service = service;
        }

        @PostMapping("/{id}/hide")
        public ResponseEntity<Void> hide(@PathVariable UUID id) {
            service.hide(id);
            return ResponseEntity.noContent().build();
        }
    }
}
