package app.basera.auth.dto;

import app.basera.security.Role;
import java.util.UUID;

public record SessionUser(UUID userId, String name, String email, String photoUrl, Role role) {}
