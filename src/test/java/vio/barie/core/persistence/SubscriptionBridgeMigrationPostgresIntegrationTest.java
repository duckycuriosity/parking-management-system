package vio.barie.core.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the target branch's actual immutable V47/V48/V50, then new V51 and canonical V55. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class SubscriptionBridgeMigrationPostgresIntegrationTest {
    @TempDir Path migrationDirectory;
    private String schema;
    private Connection database;

    @BeforeEach
    void createOnlyAnIsolatedLegacySchema() throws SQLException, IOException {
        schema = "subscription_bridge_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(administration, "CREATE SCHEMA " + schema);
        }
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        database = SubscriptionJpaTestSupport.open(schema);
        for (String migration : new String[]{
                "V47__add_owner_account_vehicle_customer.sql",
                "V48__add_owner_profile_status_columns.sql",
                "V50__add_table_subcription_parkage.sql",
                "V51__bridge_subscription_branch_schema.sql",
                "V55__move_subscription_business_rules_to_jpa.sql"}) {
            try (InputStream source = getClass().getResourceAsStream("/db/migration/" + migration)) {
                assertNotNull(source, migration);
                Files.copy(source, migrationDirectory.resolve(migration));
            }
        }
        migrate("50");
        execute("""
                INSERT INTO subscription_package
                    (id,package_code,name,version_no,parking_lot_id,vehicle_type,price_vnd,
                     duration_days,coverage_mode,effective_from,status)
                VALUES (91,'RETAINED','Retained package',1,1,'CAR',500000,30,'WEEKLY',
                        '2026-09-01T00:00:00Z','PUBLISHED')
                """);
        execute("""
                INSERT INTO subscription_package_schedule
                    (id,package_id,weekday,start_time,end_time,end_day_offset)
                VALUES (92,91,1,'22:00','06:00',1)
                """);
        execute("""
                INSERT INTO vehicle_subscription
                    (id,vehicle_id,parking_lot_id,package_id,registration_type,price_vnd,
                     starts_at,ends_at,payment_deadline_at,status)
                VALUES (93,1,1,91,'NEW',500000,'2026-10-01T00:00:00Z',
                        '2026-10-31T00:00:00Z','2026-10-02T00:00:00Z','PENDING_PAYMENT')
                """);
    }

    @AfterEach
    void dropOnlyThisTestSchema() throws SQLException {
        if (database != null) database.close();
        if (schema != null) try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(administration, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void bridgeRetainsRowsAndInstallsFinalJpaMappingsAndStructuralKeys() throws SQLException {
        migrate("55");
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription WHERE id=93 AND price_vnd=500000"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription WHERE request_key='legacy-subscription-93'"));
        assertEquals(1, number("SELECT count(*) FROM subscription_package_schedule WHERE id=92 AND end_day_offset=1"));
        assertEquals(0, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, number("SELECT count(*) FROM pg_constraint WHERE conrelid='vehicle_subscription'::regclass AND conname='uq_vehicle_subscription_request_key'"));
        assertEquals(1, number("SELECT count(*) FROM pg_constraint WHERE conrelid='vehicle_subscription_receipt'::regclass AND conname='uq_subscription_receipt_source_reference'"));
        assertEquals(1, number("SELECT count(*) FROM pg_constraint WHERE conrelid='payment'::regclass AND conname='uq_payment_invoice_no'"));
        var factory = SubscriptionJpaTestSupport.entityManagerFactory(schema, SubscriptionJpaTestSupport.allTypes(), "validate");
        factory.destroy();
    }

    @Test
    void paidLegacyRowStopsUpgradeAndRetainsFinancialHistory() throws SQLException {
        execute("UPDATE vehicle_subscription SET status='PAID',paid_at='2026-10-01T00:00:00Z',payment_reference='old-bank-ref' WHERE id=93");
        var failure = assertThrows(RuntimeException.class, () -> migrate("51"));
        assertTrue(causes(failure).contains("explicit reconciliation"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription WHERE id=93 AND payment_reference='old-bank-ref'"));
        assertEquals(0, number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='vehicle_subscription' AND column_name='request_key'"));
    }

    @Test
    void missingDeadlineDoesNotReceiveAnInventedPaymentDeadline() throws SQLException {
        execute("UPDATE vehicle_subscription SET payment_deadline_at=NULL WHERE id=93");
        assertThrows(RuntimeException.class, () -> migrate("51"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription WHERE id=93 AND payment_deadline_at IS NULL"));
    }

    @Test
    void incompatibleLongScheduleStopsUpgradeWithoutChangingClockRange() throws SQLException {
        execute("UPDATE subscription_package_schedule SET start_time='08:00',end_time='17:00',end_day_offset=1 WHERE id=92");
        var failure = assertThrows(RuntimeException.class, () -> migrate("51"));
        assertTrue(causes(failure).contains("end_day_offset"));
        assertEquals(1, number("SELECT count(*) FROM subscription_package_schedule WHERE id=92 AND end_day_offset=1"));
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(SubscriptionJpaTestSupport.url(), SubscriptionJpaTestSupport.user(), SubscriptionJpaTestSupport.password())
                .schemas(schema).defaultSchema(schema).baselineOnMigrate(true).baselineVersion("46")
                .locations("filesystem:" + migrationDirectory).target(target).load().migrate();
    }
    private void execute(String sql) throws SQLException { SubscriptionJpaTestSupport.execute(database, sql); }
    private long number(String sql) throws SQLException {
        try (var statement = database.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }
    private String causes(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) text.append(cause.getMessage()).append('\n');
        return text.toString();
    }
}
