package app.basera.common.error;

import java.time.Instant;
import java.util.List;

/** The one error body returned by every endpoint and every filter. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {}
}
