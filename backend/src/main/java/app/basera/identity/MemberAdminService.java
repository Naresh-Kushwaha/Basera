package app.basera.identity;

import app.basera.common.audit.AuditService;
import app.basera.common.error.ApiException;
import app.basera.common.web.PageResponse;
import app.basera.identity.dto.MemberResponse;
import app.basera.security.CurrentUser;
import app.basera.security.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TENANT_ADMIN member management. There is no tenant_id anywhere in this class: every repository call runs inside a
 * transaction whose session carries the tenant, so Row Level Security makes other colleges' rows unreachable
 * (findById for another tenant's membership simply returns empty -> 404).
 */
@Service
public class MemberAdminService {

    private final MembershipRepository members;
    private final UserAccountRepository users;
    private final MemberMapper mapper;
    private final AuditService audit;

    public MemberAdminService(MembershipRepository members, UserAccountRepository users, MemberMapper mapper,
                              AuditService audit) {
        this.members = members;
        this.users = users;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(Role roleFilter, Pageable pageable) {
        Page<Membership> page = roleFilter == null
                ? members.findAllByOrderByCreatedAtDesc(pageable)
                : members.findAllByRoleOrderByCreatedAtDesc(roleFilter, pageable);
        List<UUID> userIds = page.getContent().stream().map(Membership::getUserId).toList();
        Map<UUID, UserAccount> byId = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        List<MemberResponse> items = page.getContent().stream()
                .map(m -> mapper.toResponse(m, byId.get(m.getUserId())))
                .toList();
        return PageResponse.of(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /** Owner role is granted by an admin only, never self-service. */
    @Transactional
    public MemberResponse grantOwner(UUID membershipId) {
        Membership m = find(membershipId);
        if (m.getRole() != Role.STUDENT) {
            throw ApiException.conflict("INVALID_ROLE_TRANSITION", "Only a STUDENT can be granted the OWNER role");
        }
        if (m.getStatus() != MembershipStatus.ACTIVE) {
            throw ApiException.conflict("MEMBER_SUSPENDED", "Reactivate the member first");
        }
        m.setRole(Role.OWNER);
        audit.record("OWNER_GRANTED", "membership", membershipId, Map.of("userId", m.getUserId().toString()));
        return respond(m);
    }

    @Transactional
    public MemberResponse revokeOwner(UUID membershipId) {
        Membership m = find(membershipId);
        if (m.getRole() != Role.OWNER) {
            throw ApiException.conflict("INVALID_ROLE_TRANSITION", "Member is not an OWNER");
        }
        m.setRole(Role.STUDENT);
        audit.record("OWNER_REVOKED", "membership", membershipId, Map.of("userId", m.getUserId().toString()));
        return respond(m);
    }

    /** Takes effect at the member's next token refresh (access tokens live 15 minutes). */
    @Transactional
    public MemberResponse suspend(UUID membershipId) {
        Membership m = find(membershipId);
        if (m.getRole() == Role.TENANT_ADMIN) {
            throw ApiException.conflict("INVALID_ROLE_TRANSITION", "A TENANT_ADMIN cannot be suspended here");
        }
        if (membershipId.equals(CurrentUser.require().membershipId())) {
            throw ApiException.conflict("CANNOT_SUSPEND_SELF", "You cannot suspend yourself");
        }
        m.setStatus(MembershipStatus.SUSPENDED);
        audit.record("MEMBER_SUSPENDED", "membership", membershipId, Map.of("userId", m.getUserId().toString()));
        return respond(m);
    }

    @Transactional
    public MemberResponse reactivate(UUID membershipId) {
        Membership m = find(membershipId);
        m.setStatus(MembershipStatus.ACTIVE);
        audit.record("MEMBER_REACTIVATED", "membership", membershipId, Map.of("userId", m.getUserId().toString()));
        return respond(m);
    }

    private Membership find(UUID id) {
        return members.findById(id)
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "Member not found"));
    }

    private MemberResponse respond(Membership m) {
        return mapper.toResponse(m, users.findById(m.getUserId()).orElse(null));
    }
}
