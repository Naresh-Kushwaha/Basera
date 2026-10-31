package app.basera.identity.dto;

import app.basera.identity.MembershipStatus;
import app.basera.security.Role;
import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID membershipId,
        UUID userId,
        String displayName,
        String email,
        String photoUrl,
        Role role,
        MembershipStatus status,
        boolean collegeEmailVerified,
        Instant joinedAt) {}
