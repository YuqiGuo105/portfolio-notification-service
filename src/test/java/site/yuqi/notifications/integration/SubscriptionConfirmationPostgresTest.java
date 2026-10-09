package site.yuqi.notifications.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.notifications.repository.SubscriptionConfirmationRepository;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against the disposable PostgreSQL service in CI, never a production URL. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class SubscriptionConfirmationPostgresTest {
    JdbcTemplate jdbc;
    SubscriptionConfirmationRepository repository;
    TransactionTemplate transaction;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/portfolio_audit_test"), "Only the disposable loopback test database is allowed");
        var dataSource = new DriverManagerDataSource(url, "postgres", "test-password");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon; END IF; IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated; END IF; END $$");
        jdbc.execute("DROP TABLE IF EXISTS public.subscription_confirmations");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V9__subscription_confirmation.sql"));
        }
        jdbc.execute("TRUNCATE public.subscription_confirmations");
        repository = new SubscriptionConfirmationRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    boolean request(String hash) {
        return repository.request("owner@example.test", hash, List.of("ARTICLE_UPDATES"), List.of("EMAIL"));
    }

    @Test void pendingConfirmationCannotBeRotatedOrReplayed() {
        assertTrue(request("hash-one"));
        assertFalse(request("attacker-hash"));
        assertTrue(repository.consume("attacker-hash").isEmpty());
        assertEquals(Boolean.TRUE, transaction.execute(status -> repository.consume("hash-one").isPresent()));
        assertTrue(repository.consume("hash-one").isEmpty());
        assertFalse(request("throttled"));
    }

    @Test void expiredTokensCannotActivateAndRequestCanRecover() {
        assertTrue(request("old"));
        jdbc.update("update public.subscription_confirmations set expires_at = now() - interval '1 second'");
        assertTrue(repository.consume("old").isEmpty());
        assertTrue(request("new"));
        assertTrue(repository.consume("new").isPresent());
    }

    @Test void failedActivationRollsBackTokenConsumption() {
        assertTrue(request("rollback"));
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            assertTrue(repository.consume("rollback").isPresent());
            throw new IllegalStateException("simulated activation failure");
        }));
        assertTrue(repository.consume("rollback").isPresent());
    }

    @Test void concurrentConfirmationsHaveOneWinner() throws Exception {
        assertTrue(request("concurrent"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> transaction.execute(s -> repository.consume("concurrent").isPresent()));
            var b = pool.submit(() -> transaction.execute(s -> repository.consume("concurrent").isPresent()));
            assertNotEquals(a.get(), b.get());
        }
    }
}
