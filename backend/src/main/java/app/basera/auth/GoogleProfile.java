package app.basera.auth;

public record GoogleProfile(String sub, String email, boolean emailVerified, String name, String picture,
                            String hostedDomain) {}
