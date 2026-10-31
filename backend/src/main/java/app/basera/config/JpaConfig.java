package app.basera.config;

import app.basera.common.audit.AuditorAwareImpl;
import app.basera.tenancy.TenantAwareJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.orm.jpa.JpaTransactionManager;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    /**
     * The JPA transaction manager that runs set_config('app.current_tenant', ...) at the start of every
     * transaction. Replaces Spring Boot's default so that EVERY @Transactional method is tenant-scoped.
     */
    @Bean(name = "transactionManager")
    @Primary
    public JpaTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory, @Qualifier("dataSource") DataSource dataSource) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory, dataSource);
    }

    @Bean
    public AuditorAware<UUID> auditorAware() {
        return new AuditorAwareImpl();
    }
}
