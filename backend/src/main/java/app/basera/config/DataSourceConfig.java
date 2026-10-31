package app.basera.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Two pools, two database roles:
 * <ul>
 *   <li><b>dataSource</b> (primary): role basera_app. Subject to Row Level Security. Used by JPA and by
 *       every tenant-scoped request.</li>
 *   <li><b>platformDataSource</b>: role basera_platform (BYPASSRLS, table-restricted). Reachable only through
 *       {@code app.basera.platform.PlatformJdbc}, which ArchUnit restricts to the platform/auth/tenancy packages.</li>
 * </ul>
 * Flyway uses its own connection as basera_owner (see spring.flyway.* in application.yml).
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("basera.datasource.app")
    public HikariDataSource dataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "platformDataSource")
    @ConfigurationProperties("basera.datasource.platform")
    public HikariDataSource platformDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /** JdbcClient on the RLS-subject pool. Joins the JPA transaction, so it sees the same tenant context. */
    @Bean
    @Primary
    public JdbcClient appJdbcClient(@Qualifier("dataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean(name = "platformTx")
    public DataSourceTransactionManager platformTransactionManager(
            @Qualifier("platformDataSource") DataSource platformDataSource) {
        return new DataSourceTransactionManager(platformDataSource);
    }
}
