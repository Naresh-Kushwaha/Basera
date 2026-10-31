package app.basera.identity.dto;

import app.basera.security.Role;
import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(UUID id, String email, Role role, Instant expiresAt, String loginUrl) {}
