package site.yuqi.notifications.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway startup strategy: always run {@code repair()} before {@code migrate()}.
 *
 * <p>This silently re-syncs the {@code flyway_schema_history} checksum column
 * when a past V*__*.sql file is modified (whitespace, line endings, comments)
 * in a way that does not actually change the resulting schema. Without this,
 * any such edit makes Flyway throw {@code FlywayValidateException} on startup
 * and Spring fails to wire the {@code flywayInitializer} bean, which prevents
 * Cloud Run from binding port 8080 and fails the deploy.
 *
 * <p>Spring Boot 3.4 ships a built-in property {@code spring.flyway.repair-on-migrate}
 * that does the same thing — this bean is the back-port for Spring Boot 3.3.x.
 * Remove it (and switch to the property) after the next Spring Boot upgrade.
 */
@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairBeforeMigrate() {
        return flyway -> {
            log.info("Flyway: running repair() before migrate() to re-sync schema history checksums.");
            flyway.repair();
            flyway.migrate();
        };
    }
}
