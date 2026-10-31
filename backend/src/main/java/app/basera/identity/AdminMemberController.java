package app.basera.identity;

import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import app.basera.identity.dto.InvitationResponse;
import app.basera.identity.dto.InviteOwnerRequest;
import app.basera.identity.dto.MemberResponse;
import app.basera.security.Role;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** TENANT_ADMIN endpoints (SecurityConfig enforces hasRole('TENANT_ADMIN') on /api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMemberController {

    private final MemberAdminService members;
    private final InvitationService invitations;

    public AdminMemberController(MemberAdminService members, InvitationService invitations) {
        this.members = members;
        this.invitations = invitations;
    }

    @GetMapping("/members")
    public PageResponse<MemberResponse> list(@RequestParam(name = "role", required = false) Role role,
                                             @RequestParam(name = "page", defaultValue = "0") int page,
                                             @RequestParam(name = "size", defaultValue = "20") int size) {
        return members.list(role, Paging.of(page, size));
    }

    @PostMapping("/members/{id}/grant-owner")
    public MemberResponse grantOwner(@PathVariable UUID id) {
        return members.grantOwner(id);
    }

    @PostMapping("/members/{id}/revoke-owner")
    public MemberResponse revokeOwner(@PathVariable UUID id) {
        return members.revokeOwner(id);
    }

    @PostMapping("/members/{id}/suspend")
    public MemberResponse suspend(@PathVariable UUID id) {
        return members.suspend(id);
    }

    @PostMapping("/members/{id}/reactivate")
    public MemberResponse reactivate(@PathVariable UUID id) {
        return members.reactivate(id);
    }

    @GetMapping("/invitations")
    public List<InvitationResponse> openInvitations() {
        return invitations.listOpen();
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@Valid @RequestBody InviteOwnerRequest request) {
        return invitations.inviteOwner(request.email());
    }

    @DeleteMapping("/invitations/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        invitations.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
