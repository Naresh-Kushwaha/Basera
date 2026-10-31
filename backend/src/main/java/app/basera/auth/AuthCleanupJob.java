package app.basera.auth;

import app.basera.platform.PlatformJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Hourly housekeeping: removes long-expired one-time codes and refresh tokens. Safe to run on every replica. */
@Component
public class AuthCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuthCleanupJob.class);

    private final PlatformJdbc db;

    public AuthCleanupJob(PlatformJdbc db) {
        this.db = db;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional(transactionManager = "platformTx")
    public void purgeExpired() {
        int codes = db.client().sql("DELETE FROM auth_code WHERE expires_at < now() - interval '1 day'").update();
        int tokens = db.client().sql("DELETE FROM refresh_token WHERE expires_at < now() - interval '7 days'").update();
        if (codes + tokens > 0) {
            log.info("Purged {} expired auth codes and {} expired refresh tokens", codes, tokens);
        }
    }
}
