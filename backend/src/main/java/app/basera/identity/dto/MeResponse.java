package app.basera.identity.dto;

import app.basera.identity.MembershipStatus;
import app.basera.security.Role;
import java.util.UUID;

public record MeResponse(
        UUID userId,
        UUID membershipId,
        UUID tenantId,
        Role role,
        MembershipStatus status,
        String displayName,
        String email,
        String photoUrl,
        boolean collegeEmailVerified) {}
