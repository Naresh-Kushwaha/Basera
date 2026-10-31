package app.basera.storage;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves listing photos. Public GET so plain img tags work; the unguessable random key (122 bits) is the capability.
 * Photos are re-encoded server-side, so the bytes served are never attacker-controlled file formats.
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final StorageService storage;

    public MediaController(StorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/{key}")
    public ResponseEntity<byte[]> get(@PathVariable String key) {
        return storage.get(key)
                .map(s -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(s.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(s.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
