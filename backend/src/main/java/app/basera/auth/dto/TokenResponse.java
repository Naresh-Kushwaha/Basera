package app.basera.auth.dto;

/** The refresh token is NOT here: it travels only in an HttpOnly cookie. */
public record TokenResponse(String accessToken, String tokenType, long expiresIn, SessionUser user) {}
