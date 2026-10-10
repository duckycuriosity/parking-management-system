package vio.barie.core.persistence;

import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.SubscriptionPackageScheduleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;
import vio.barie.core.entity.VehicleSubscriptionReceiptEntity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disposable PostgreSQL fixture only. Production data access is JPA; SQL here creates schemas,
 * applies real Flyway migrations and observes committed rows without an ORM first-level cache.
 * Scanning real entity mappings includes legacy associations needed by the pure JPQL queries.
 * No application configuration, gateway bean or production datasource is loaded.
 */
final class SubscriptionJpaTestSupport {
    private static final Set<String> SUBSCRIPTION_TYPES = Set.of(
            SubscriptionPackageEntity.class.getName(), SubscriptionPackageScheduleEntity.class.getName(),
            VehicleSubscriptionEntity.class.getName(), VehicleSubscriptionReceiptEntity.class.getName());

    private SubscriptionJpaTestSupport() { }

    static List<String> allTypes() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return scanner.findCandidateComponents("vio.barie.core.entity").stream()
                .map(bean -> bean.getBeanClassName()).sorted().toList();
    }

    static List<String> legacyTypes() {
        return allTypes().stream().filter(type -> !SUBSCRIPTION_TYPES.contains(type)).toList();
    }

    static void createLegacySchemaAndSeed(String schema) throws SQLException {
        LocalContainerEntityManagerFactoryBean legacy = entityManagerFactory(schema, legacyTypes(), "create-only");
        legacy.destroy();
        try (Connection connection = open(schema)) {
            // Current PaymentEntity declares the V55 uniqueness contract. The legacy fixture
            // must represent pre-V55 state so Flyway, rather than Hibernate, adds it below.
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
        }
    }

    static void migrate(String schema, String target) {
        Flyway.configure().dataSource(url(), user(), password()).schemas(schema).defaultSchema(schema)
                .createSchemas(false).baselineOnMigrate(true).baselineVersion("53").target(target)
                // These historical domain tests verify the finalized V54 -> V55 contract.
                // Production dangpn/subscription uses the V50 -> V51 compatibility bridge;
                // its real upgrade path is exercised by owner-registration migration tests.
                .locations("classpath:db/subscription-history").load().migrate();
    }

    static LocalContainerEntityManagerFactoryBean entityManagerFactory(String schema, List<String> types, String action) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return open(schema); }
            @Override public Connection getConnection(String username, String password) throws SQLException { return open(schema); }
        });
        factory.setManagedTypes(PersistenceManagedTypes.of(types.toArray(String[]::new)));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", action, "hibernate.hbm2ddl.halt_on_error", true));
        factory.afterPropertiesSet();
        return factory;
    }

    static Connection openRaw() throws SQLException {
        return DriverManager.getConnection(url(), user(), password());
    }

    static Connection open(String schema) throws SQLException {
        if (!schema.matches("[a-z_0-9]+")) throw new IllegalArgumentException("Invalid disposable schema");
        Connection connection = openRaw();
        execute(connection, "SET search_path TO " + schema + ", public");
        execute(connection, "SET application_name TO '" + schema + "'");
        return connection;
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.executeUpdate(); }
    }

    static String url() { return System.getenv("SUBSCRIPTION_TEST_JDBC_URL"); }
    static String user() { return System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_USER", "subscription_test"); }
    static String password() { return System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_PASSWORD", "subscription_test"); }
}

