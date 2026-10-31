package app.basera.platform;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * THE only gateway to the RLS-bypassing database role (basera_platform).
 * ArchUnit (ArchitectureTest) fails the build if any class outside app.basera.platform, app.basera.auth
 * or app.basera.tenancy depends on this class. Writes through it must use @Transactional("platformTx").
 */
@Component
public class PlatformJdbc {

    private final JdbcClient client;

    public PlatformJdbc(@Qualifier("platformDataSource") DataSource platformDataSource) {
        this.client = JdbcClient.create(platformDataSource);
    }

    public JdbcClient client() {
        return client;
    }
}
