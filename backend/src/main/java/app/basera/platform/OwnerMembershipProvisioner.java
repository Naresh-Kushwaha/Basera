package app.basera.platform;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a college admin approves an owner's listing, that owner needs an OWNER membership in THIS college (owners have
 * no membership there before: they are onboarded by another college). Creating a membership for a user who has none yet
 * cannot be done by tenant-scoped code (RLS hides that user), so it goes through the platform pool, narrowly:
 * only (tenant, user) pairs the caller already authorised by approving a listing.
 */
@Service
public class OwnerMembershipProvisioner {

    private final PlatformJdbc db;

    public OwnerMembershipProvisioner(PlatformJdbc db) {
        this.db = db;
    }

    @Transactional(transactionManager = "platformTx")
    public void ensureOwner(UUID tenantId, UUID userId) {
        db.client().sql("""
                INSERT INTO membership (tenant_id, user_id, role, display_name, photo_url)
                SELECT :t, u.id, 'OWNER', left(u.full_name, 100), u.photo_url FROM user_account u WHERE u.id = :u
                ON CONFLICT (tenant_id, user_id) DO UPDATE
                    SET role = CASE WHEN membership.role = 'STUDENT' THEN 'OWNER' ELSE membership.role END
                """)
                .param("t", tenantId).param("u", userId).update();
    }
}
