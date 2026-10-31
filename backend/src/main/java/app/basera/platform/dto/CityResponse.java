package app.basera.platform.dto;

import java.util.UUID;

public record CityResponse(UUID id, String name, String state, String countryCode) {}
