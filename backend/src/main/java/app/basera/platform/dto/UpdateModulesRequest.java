package app.basera.platform.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UpdateModulesRequest(@NotNull List<String> modules) {}
