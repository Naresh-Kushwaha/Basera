package app.basera.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL 16 + PostGIS container shared by every integration test in the JVM (singleton pattern).
 * Needs Docker. Migrations run as basera_owner, exactly like production; tests then connect as the real
 * runtime roles (basera_app / basera_platform) so Row Level Security is genuinely exercised.
 */
public final class TestPostgres {

    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("basera")
            .withUsername("su")
            .withPassword("su")
            .withInitScript("test-init.sql");

    static {
        CONTAINER.start();
        Flyway.configure()
                .dataSource(CONTAINER.getJdbcUrl(), "basera_owner", "owner")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private TestPostgres() {}

    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    /** Superuser connection: bypasses RLS, used ONLY to seed and to inspect data in assertions. */
    public static Connection superuser() throws SQLException {
        return DriverManager.getConnection(CONTAINER.getJdbcUrl(), "su", "su");
    }

    public static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(CONTAINER.getJdbcUrl(), user, password);
    }
}
