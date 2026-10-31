package app.basera.tenancy;

import app.basera.security.AuthenticatedUser;
import app.basera.security.CurrentUser;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * THE mechanism that makes Row Level Security work.
 *
 * Right after every transaction begins, it runs
 *     SELECT set_config('app.current_tenant', ?, true), set_config('app.current_user', ?, true),
 *            set_config('app.current_role', ?, true)
 * on the SAME connection the transaction uses. The third argument "true" makes each setting
 * TRANSACTION-LOCAL: it disappears at commit/rollback, so a pooled connection can never carry one
 * request's tenant into the next (a plain session-level SET could).
 *
 * Fail-closed: no tenant context means an empty string, the policies compare against NULL, and the
 * query returns no rows. Code that touches the database outside a transaction gets no tenant and
 * therefore no data, rather than someone else's data.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    private static final String SET_CONTEXT = """
            SELECT set_config('app.current_tenant', ?, true),
                   set_config('app.current_user',   ?, true),
                   set_config('app.current_role',   ?, true)
            """;

    private final DataSource appDataSource;

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf, DataSource appDataSource) {
        super(emf);
        this.appDataSource = appDataSource;
        setDataSource(appDataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Connection con = DataSourceUtils.getConnection(appDataSource);
        try (PreparedStatement ps = con.prepareStatement(SET_CONTEXT)) {
            TenantContext ctx = TenantContextHolder.get();
            Optional<AuthenticatedUser> user = CurrentUser.get();

            // Defence in depth: only honour the tenant if the authenticated user's token is bound to it.
            boolean consistent = ctx.isTenant()
                    && (user.isEmpty() || ctx.tenantId().equals(user.get().tenantId()));
            ps.setString(1, consistent ? ctx.tenantId().toString() : "");
            ps.setString(2, user.map(u -> u.userId().toString()).orElse(""));
            ps.setString(3, user.map(u -> u.role().name()).orElse(""));
            ps.execute();
        } catch (SQLException | RuntimeException e) {
            try {
                doCleanupAfterCompletion(transaction);
            } catch (RuntimeException ignored) {
                // best effort: the original failure is what matters
            }
            throw new CannotCreateTransactionException("Could not set the tenant session context", e);
        } finally {
            DataSourceUtils.releaseConnection(con, appDataSource);
        }
    }
}
