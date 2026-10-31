package app.basera.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import app.basera.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class MessagingController {

    public record SendRequest(String message) {}

    private final MessagingService service;

    public MessagingController(MessagingService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> start(@RequestBody MessagingService.Start request) {
        return Map.of("id", service.start(request));
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable UUID id, @RequestParam(required = false) String after) {
        try {
            return service.messages(id, after == null || after.isBlank() ? null : Instant.parse(after));
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "after must be an ISO-8601 instant");
        }
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<Void> send(@PathVariable UUID id, @RequestBody SendRequest request) {
        service.send(id, request.message());
        return ResponseEntity.noContent().build();
    }
}
