package app.basera.platform;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends queued emails. Lives in the platform package because it resolves a membership's email address at SEND time
 * (tenant code can never read other users' addresses). Rows are claimed with FOR UPDATE SKIP LOCKED, so several
 * replicas never send the same email twice. Retries: up to 5 attempts, 5 minutes apart per attempt.
 * With no SMTP configured (spring.mail.host unset) rows stay PENDING; basera.mail.log-only=true (dev) logs and marks them SENT.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final PlatformJdbc db;
    private final ObjectProvider<JavaMailSender> mailProvider;
    private final String from;
    private final boolean logOnly;

    public EmailDispatcher(PlatformJdbc db, ObjectProvider<JavaMailSender> mailProvider,
                           @Value("${basera.mail.from:no-reply@localhost}") String from,
                           @Value("${basera.mail.log-only:false}") boolean logOnly) {
        this.db = db;
        this.mailProvider = mailProvider;
        this.from = from;
        this.logOnly = logOnly;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT45S")
    @Transactional(transactionManager = "platformTx")
    public void dispatch() {
        JavaMailSender mail = mailProvider.getIfAvailable();
        if (mail == null && !logOnly) {
            return;
        }
        List<Map<String, Object>> batch = db.client().sql("""
                SELECT o.id, o.subject, o.body, COALESCE(o.to_email, u.email) AS email, t.name AS college, o.attempts
                FROM email_outbox o
                JOIN tenant t ON t.id = o.tenant_id
                LEFT JOIN membership m ON m.id = o.recipient_membership_id AND m.tenant_id = o.tenant_id
                LEFT JOIN user_account u ON u.id = m.user_id
                WHERE o.status = 'PENDING' AND o.send_after <= now()
                ORDER BY o.created_at LIMIT 50
                FOR UPDATE OF o SKIP LOCKED""").query().listOfRows();
        for (Map<String, Object> row : batch) {
            Object id = row.get("id");
            int attempts = ((Number) row.get("attempts")).intValue();
            try {
                String to = (String) row.get("email");
                if (to == null) {
                    throw new IllegalStateException("recipient has no email address");
                }
                String text = row.get("body") + "\n\n-- " + row.get("college") + " on Basera\nYou can switch off email notifications in your profile settings.";
                if (mail == null) {
                    log.info("[log-only email] to={} subject={}\n{}", to, row.get("subject"), text);
                } else {
                    SimpleMailMessage m = new SimpleMailMessage();
                    m.setFrom(from);
                    m.setTo(to);
                    m.setSubject((String) row.get("subject"));
                    m.setText(text);
                    mail.send(m);
                }
                db.client().sql("UPDATE email_outbox SET status = 'SENT', sent_at = now(), attempts = attempts + 1 WHERE id = :id")
                        .param("id", id).update();
            } catch (Exception e) {
                boolean giveUp = attempts + 1 >= 5;
                db.client().sql("""
                        UPDATE email_outbox SET attempts = attempts + 1, last_error = left(:err, 500),
                               status = CASE WHEN :giveUp THEN 'FAILED' ELSE 'PENDING' END,
                               send_after = now() + make_interval(mins => 5 * (attempts + 1))
                        WHERE id = :id""")
                        .param("err", String.valueOf(e.getMessage())).param("giveUp", giveUp).param("id", id).update();
                log.warn("Email {} failed (attempt {}): {}", id, attempts + 1, e.getMessage());
            }
        }
    }
}
