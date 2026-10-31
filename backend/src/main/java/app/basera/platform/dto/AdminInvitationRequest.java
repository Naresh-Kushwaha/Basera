package app.basera.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminInvitationRequest(@NotBlank @Email @Size(max = 200) String email) {}
