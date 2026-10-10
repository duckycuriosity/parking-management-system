package vio.barie.core.persistence;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Disposable legacy fixture plus the actual owner-registration/AI migrations. */
final class OwnerAccountProfileJpaTestSupport {
    private OwnerAccountProfileJpaTestSupport() { }

    static void createSchemaAndMigrate(String schema) throws SQLException, IOException {
        createSchemaAndMigrate(schema, connection -> { });
    }

    @FunctionalInterface
    interface LegacyRows {
        void insert(Connection connection) throws SQLException;
    }

    static void createSchemaAndMigrate(String schema, LegacyRows legacyRows) throws SQLException, IOException {
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        SubscriptionJpaTestSupport.migrate(schema, "55");
        try (Connection connection = SubscriptionJpaTestSupport.open(schema)) {
            // Hibernate creates today's legacy mappings. Remove only new owner columns so
            // the production migrations, rather than hbm2ddl, must create and constrain them.
            SubscriptionJpaTestSupport.execute(connection, "DROP TABLE IF EXISTS owner_profile_registration");
            SubscriptionJpaTestSupport.execute(connection, """
                    ALTER TABLE owner_account
                      DROP COLUMN ai_profile_id, DROP COLUMN ai_identity_id,
                      DROP COLUMN driver_customer_id, DROP COLUMN ai_sync_state,
                      DROP COLUMN ai_sync_token, DROP COLUMN ai_sync_started_at,
                      DROP COLUMN ai_sync_profile_id, DROP COLUMN row_version
                    """);
            SubscriptionJpaTestSupport.execute(connection, """
                    ALTER TABLE parking_customer
                      DROP COLUMN ai_sync_state, DROP COLUMN ai_sync_token,
                      DROP COLUMN ai_sync_started_at, DROP COLUMN ai_sync_profile_id,
                      DROP COLUMN row_version
                    """);
            SubscriptionJpaTestSupport.execute(connection,
                    "CREATE UNIQUE INDEX uk_owner_account_phone ON owner_account(phone)");
            SubscriptionJpaTestSupport.execute(connection, """
                    CREATE UNIQUE INDEX uk_owner_account_identity_number ON owner_account(identity_number)
                    WHERE identity_number IS NOT NULL
                    """);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT setval(pg_get_serial_sequence('vehicle', 'id'), 2, true)");
            }
            legacyRows.insert(connection);
        }
        Path migrations = Files.createTempDirectory("owner-profile-migrations-");
        try {
            copyMigration("V57__owner_profile_registration.sql", migrations);
            copyMigration("V58__owner_account_ai_identity_sync.sql", migrations);
            Flyway.configure()
                    .dataSource(System.getenv("SUBSCRIPTION_TEST_JDBC_URL"),
                            System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_USER", "subscription_test"),
                            System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_PASSWORD", "subscription_test"))
                    .schemas(schema).defaultSchema(schema).createSchemas(false)
                    // Historical domain fixtures live in an isolated immutable location;
                    // only the two actual new production migrations are loaded here.
                    .validateOnMigrate(false).target("58")
                    .locations("filesystem:" + migrations.toAbsolutePath()).load().migrate();
        } finally {
            try (var files = Files.list(migrations)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(migrations);
        }
    }

    private static void copyMigration(String name, Path directory) throws IOException {
        try (var input = OwnerAccountProfileJpaTestSupport.class.getResourceAsStream("/db/migration/" + name)) {
            if (input == null) throw new IOException("Missing production migration " + name);
            Files.copy(input, directory.resolve(name));
        }
    }
}
