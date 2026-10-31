package app.basera.platform.dto;

import java.util.UUID;

public record PlatformMeResponse(UUID userId, String name, String email, String photoUrl) {}
