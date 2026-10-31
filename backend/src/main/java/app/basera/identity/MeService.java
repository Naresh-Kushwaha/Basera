package app.basera.identity;

import app.basera.common.error.ApiException;
import app.basera.identity.dto.MeResponse;
import app.basera.security.AuthenticatedUser;
import app.basera.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeService {

    private final MembershipRepository members;
    private final UserAccountRepository users;

    public MeService(MembershipRepository members, UserAccountRepository users) {
        this.members = members;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        AuthenticatedUser me = CurrentUser.require();
        Membership m = members.findById(me.membershipId())
                .orElseThrow(() -> ApiException.notFound("MEMBERSHIP_NOT_FOUND", "Membership not found"));
        UserAccount u = users.findById(me.userId())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
        return new MeResponse(u.getId(), m.getId(), m.getTenantId(), m.getRole(), m.getStatus(), m.getDisplayName(),
                u.getEmail(), m.getPhotoUrl() != null ? m.getPhotoUrl() : u.getPhotoUrl(),
                m.getCollegeEmailVerifiedAt() != null);
    }
}
