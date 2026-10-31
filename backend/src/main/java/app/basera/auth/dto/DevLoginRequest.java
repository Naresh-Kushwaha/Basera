package app.basera.auth.dto;

import app.basera.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DevLoginRequest(
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(max = 100) String name,
        @NotNull Role role) {}
