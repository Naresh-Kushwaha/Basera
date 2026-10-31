package app.basera.tenancy;

import app.basera.common.error.ApiException;
import java.util.function.Supplier;

/** Request-scoped tenant context (a ThreadLocal set by TenantResolutionFilter and always cleared in finally). */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static TenantContext get() {
        TenantContext ctx = HOLDER.get();
        return ctx == null ? TenantContext.NONE : ctx;
    }

    public static void set(TenantContext ctx) {
        HOLDER.set(ctx);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** For tenant-scoped endpoints: fails with 400 unless the request resolved to a college tenant. */
    public static TenantContext requireTenant() {
        TenantContext ctx = get();
        if (!ctx.isTenant()) {
            throw ApiException.badRequest("TENANT_REQUIRED", "This endpoint must be called on a college host");
        }
        return ctx;
    }

    /** Runs work (e.g. a background job) inside an explicit tenant context, restoring the previous one after. */
    public static <T> T callAs(TenantContext ctx, Supplier<T> work) {
        TenantContext previous = HOLDER.get();
        HOLDER.set(ctx);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
