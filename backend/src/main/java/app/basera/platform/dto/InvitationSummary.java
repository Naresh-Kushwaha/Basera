package app.basera.platform.dto;

import java.time.Instant;

/** loginUrl is where the invited person signs in with Google; invitation email delivery arrives with Phase 4. */
public record InvitationSummary(String email, String role, Instant expiresAt, String loginUrl) {}
