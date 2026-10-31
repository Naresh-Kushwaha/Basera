package app.basera.security;

public enum Role {
    SUPER_ADMIN,   // platform-wide; lives in platform_admin, not in membership
    TENANT_ADMIN,
    OWNER,
    STUDENT
}
