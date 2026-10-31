package app.basera.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The identity carried by a verified access token. tenantId/membershipId are null for SUPER_ADMIN. */
public record AuthenticatedUser(UUID userId, Role role, UUID tenantId, UUID membershipId) {

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString("role")),
                uuidOrNull(jwt.getClaimAsString("tid")),
                uuidOrNull(jwt.getClaimAsString("mid")));
    }

    private static UUID uuidOrNull(String value) {
        return (value == null || value.isBlank()) ? null : UUID.fromString(value);
    }
}
