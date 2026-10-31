package app.basera.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** type = transport | health | emergency | safe-areas | kyc. SecurityConfig restricts /api/v1/platform/** to SUPER_ADMIN. */
@RestController
@RequestMapping("/api/v1/platform/cities/{cityId}/content/{type}")
public class CityContentController {

    private final CityContentService service;

    public CityContentController(CityContentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID cityId, @PathVariable String type) {
        return service.list(cityId, type);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable UUID cityId, @PathVariable String type, @RequestBody Map<String, Object> body) {
        return Map.of("id", service.create(cityId, type, body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID cityId, @PathVariable String type, @PathVariable UUID id,
                                       @RequestBody Map<String, Object> body) {
        service.update(cityId, type, id, body);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID cityId, @PathVariable String type, @PathVariable UUID id) {
        service.delete(cityId, type, id);
        return ResponseEntity.noContent().build();
    }
}
