package app.basera.identity;

import app.basera.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    List<Invitation> findAllByConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Instant now);

    boolean existsByEmailAndRoleAndConsumedAtIsNull(String email, Role role);
}
