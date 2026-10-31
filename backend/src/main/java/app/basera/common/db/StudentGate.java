package app.basera.common.db;

import static app.basera.common.db.Db.p;

import app.basera.common.error.ApiException;
import app.basera.security.Role;
import app.basera.tenancy.TenantInfo;
import app.basera.tenancy.TenantLookupService;
import org.springframework.stereotype.Component;

/** If the college set require_verified_student, students must have a verified college email to review or post. */
@Component
public class StudentGate {

    private final Db db;
    private final TenantLookupService tenants;

    public StudentGate(Db db, TenantLookupService tenants) {
        this.db = db;
        this.tenants = tenants;
    }

    public void requireVerifiedIfConfigured() {
        if (Ctx.me().role() != Role.STUDENT) {
            return;
        }
        TenantInfo t = tenants.findById(Ctx.tenantId())
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Unknown college"));
        if (t.requireVerifiedStudent() && db.count(
                "SELECT count(*) FROM membership WHERE id = :m AND college_email_verified_at IS NOT NULL",
                p("m", Ctx.membershipId())) == 0) {
            throw ApiException.forbidden("VERIFICATION_REQUIRED",
                    "Your college requires a verified college email for this action");
        }
    }
}
