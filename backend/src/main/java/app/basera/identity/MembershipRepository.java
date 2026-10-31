package app.basera.identity;

import app.basera.security.Role;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** No tenant_id in any query: Row Level Security scopes every statement to the current tenant. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Page<Membership> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Membership> findAllByRoleOrderByCreatedAtDesc(Role role, Pageable pageable);
}
