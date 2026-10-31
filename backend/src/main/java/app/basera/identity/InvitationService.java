package app.basera.identity;

import app.basera.common.audit.AuditService;
import app.basera.common.error.ApiException;
import app.basera.identity.dto.InvitationResponse;
import app.basera.security.Role;
import app.basera.tenancy.TenantContext;
import app.basera.tenancy.TenantContextHolder;
import app.basera.tenancy.TenantUrls;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-authorises an OWNER: when a Google account with this VERIFIED email next logs in to this college, it receives the
 * OWNER role (consumed in LoginService). TENANT_ADMINs are invited only by SUPER_ADMIN (platform).
 * Email delivery of the invitation arrives with the notification module in Phase 4; for now the admin shares loginUrl.
 */
@Service
public class InvitationService {

    private static final int VALID_DAYS = 30;

    private final InvitationRepository invitations;
    private final AuditService audit;
    private final TenantUrls urls;

    public InvitationService(InvitationRepository invitations, AuditService audit, TenantUrls urls) {
        this.invitations = invitations;
        this.audit = audit;
        this.urls = urls;
    }

    @Transactional
    public InvitationResponse inviteOwner(String rawEmail) {
        TenantContext ctx = TenantContextHolder.requireTenant();
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (invitations.existsByEmailAndRoleAndConsumedAtIsNull(email, Role.OWNER)) {
            throw ApiException.conflict("INVITATION_EXISTS", "An open OWNER invitation already exists for that email");
        }
        Invitation saved = invitations.save(
                new Invitation(ctx.tenantId(), email, Role.OWNER, Instant.now().plus(VALID_DAYS, ChronoUnit.DAYS)));
        audit.record("OWNER_INVITED", "invitation", saved.getId(), Map.of("email", email));
        return toResponse(saved, ctx.slug());
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listOpen() {
        String slug = TenantContextHolder.requireTenant().slug();
        return invitations.findAllByConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Instant.now()).stream()
                .map(i -> toResponse(i, slug)).toList();
    }

    @Transactional
    public void revoke(UUID id) {
        Invitation invitation = invitations.findById(id)
                .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found"));
        if (invitation.getConsumedAt() != null) {
            throw ApiException.conflict("INVITATION_USED", "That invitation has already been used");
        }
        invitations.delete(invitation);
        audit.record("INVITATION_REVOKED", "invitation", id, Map.of("email", invitation.getEmail()));
    }

    private InvitationResponse toResponse(Invitation i, String slug) {
        return new InvitationResponse(i.getId(), i.getEmail(), i.getRole(), i.getExpiresAt(), urls.tenantBase(slug));
    }
}
