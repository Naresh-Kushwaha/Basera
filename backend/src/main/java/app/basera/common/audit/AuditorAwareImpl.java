package app.basera.common.audit;

import app.basera.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.AuditorAware;

/** The "who" for created_by / updated_by: the authenticated user id, or empty for system actions. */
public class AuditorAwareImpl implements AuditorAware<UUID> {

    @Override
    public Optional<UUID> getCurrentAuditor() {
        return CurrentUser.get().map(u -> u.userId());
    }
}
