package vio.barie.core.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Disposable fixture: generate only the pre-V52 legacy mappings, then apply the
 * actual V52 onward SQL migrations. No application profile, gateway, or production
 * datasource is loaded. Fixture SQL creates/observes data; services use real JPA.
 */
final class ParkingFeeJpaTestSupport {
    private static final Set<String> MIGRATED_TYPES = Set.of(
            "vio.barie.core.entity.FeePolicyEntity",
            "vio.barie.core.entity.FeeTimeBandEntity",
            "vio.barie.core.entity.FeeCalculationEntity",
            "vio.barie.core.entity.ParkingFeePaymentEntity",
            "vio.barie.core.entity.ParkingFeeTransactionKeyEntity",
            "vio.barie.core.entity.SubscriptionPackageEntity",
            "vio.barie.core.entity.SubscriptionPackageScheduleEntity",
            "vio.barie.core.entity.VehicleSubscriptionEntity",
            "vio.barie.core.entity.VehicleSubscriptionReceiptEntity");

    private ParkingFeeJpaTestSupport() { }

    static List<String> allTypes() {
        return SubscriptionJpaTestSupport.allTypes();
    }

    static void createLegacySchemaAndSeed(String schema) throws SQLException {
        List<String> types = allTypes().stream().filter(type -> !MIGRATED_TYPES.contains(type)).toList();
        LocalContainerEntityManagerFactoryBean legacy = entityManagerFactory(schema, types, "create-only");
        legacy.destroy();
        try (Connection connection = open(schema)) {
            // The current entity includes V55 uniqueness; let the real migration add it.
            execute(connection, "ALTER TABLE payment DROP CONSTRAINT IF EXISTS uq_payment_invoice_no");
            execute(connection, "INSERT INTO building (id, name) VALUES (1, 'Main building'), (2, 'Other building')");
            execute(connection, """
                    INSERT INTO parking_lot (id, name, building_id, verification_mode)
                    VALUES (1, 'Main lot', 1, 'FACE_PLATE'), (2, 'Other lot', 2, 'FACE_PLATE')
                    """);
            execute(connection, """
                    INSERT INTO vehicle (id, plate_number, vehicle_type)
                    VALUES (1, '30A12345', 'CAR'), (2, '30A67890', 'CAR')
                    """);
            execute(connection, """
                    INSERT INTO parking_session (id, parking_lot_id, vehicle_id, entry_time, session_type)
                    VALUES (1, 1, 1, '2026-10-09 08:00:00', 'DAY'),
                           (2, 1, 2, '2026-10-09 08:00:00', 'DAY')
                    """);
        }
    }

    static void migrate(String schema, String target) {
        Flyway.configure().dataSource(url(), user(), password()).schemas(schema).defaultSchema(schema)
                .createSchemas(false).baselineOnMigrate(true).baselineVersion("51").target(target)
                // Finalized fee/subscription history is an isolated test fixture.
                // Production target-branch V50 -> V51 is tested separately.
                .locations("classpath:db/subscription-history").load().migrate();
    }

    static LocalContainerEntityManagerFactoryBean entityManagerFactory(String schema, List<String> types, String action) {
        return SubscriptionJpaTestSupport.entityManagerFactory(schema, types, action);
    }

    static Connection openRaw() throws SQLException {
        return SubscriptionJpaTestSupport.openRaw();
    }

    static Connection open(String schema) throws SQLException {
        return SubscriptionJpaTestSupport.open(schema);
    }

    static void execute(Connection connection, String sql) throws SQLException {
        SubscriptionJpaTestSupport.execute(connection, sql);
    }

    private static String url() { return System.getenv("SUBSCRIPTION_TEST_JDBC_URL"); }
    private static String user() { return System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_USER", "subscription_test"); }
    private static String password() { return System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_PASSWORD", "subscription_test"); }
}

