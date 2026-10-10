package vio.barie.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import vio.barie.core.service.VehicleSubscriptionListingService;
import vio.barie.core.service.VehicleSubscriptionService;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.repository.VehicleSubscriptionReceiptRepository;
import vio.barie.core.repository.PaymentRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual JPQL/Criteria on PostgreSQL, including driver fan-out and empty pages. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class VehicleSubscriptionListingPostgresIntegrationTest {
    private String schema;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate transaction;
    private VehicleSubscriptionListingService listing;
    private VehicleSubscriptionRepository subscriptions;
    private VehicleSubscriptionService subscriptionService;

    @BeforeEach
    void createOnlyIsolatedListingFixture() throws SQLException {
        schema = "subscription_listing_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(administration, "CREATE SCHEMA " + schema);
        }
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        SubscriptionJpaTestSupport.migrate(schema, "55");
        try (Connection connection = SubscriptionJpaTestSupport.open(schema)) {
            execute(connection, """
                    INSERT INTO subscription_package
                        (id,package_code,name,version_no,parking_lot_id,vehicle_type,price_vnd,
                         duration_days,coverage_mode,effective_from,status)
                    VALUES (1,'LIST','Monthly',1,1,'CAR',100000,30,'ALL_DAY','2026-09-01T00:00:00Z','PUBLISHED')
                    """);
            execute(connection, """
                    INSERT INTO vehicle_subscription
                        (id,request_key,vehicle_id,parking_lot_id,package_id,registration_type,
                         price_vnd,starts_at,ends_at,payment_deadline_at,status,created_at)
                    VALUES (1,'list-first',1,1,1,'NEW',100000,'2026-10-01T00:00:00Z',
                                '2026-10-31T00:00:00Z','2026-10-02T00:00:00Z','PENDING_PAYMENT','2026-10-01T00:00:00Z'),
                           (2,'list-second',2,1,1,'NEW',100000,'2026-10-01T00:00:00Z',
                                '2026-10-31T00:00:00Z','2026-10-02T00:00:00Z','PENDING_PAYMENT','2026-10-01T00:00:00Z')
                    """);
            execute(connection, """
                    INSERT INTO parking_customer (id,username,ai_profile_id,ai_sync_state,row_version)
                    VALUES (81,'Matching Driver',1001,'NOT_SYNCED',0),(82,'Matching Driver Two',1002,'NOT_SYNCED',0)
                    """);
            execute(connection, """
                    INSERT INTO vehicle_customer (id,vehicle_id,customer_id,relation_type,status,created_at,updated_at)
                    VALUES (91,1,81,'AUTHORIZED_USER','ACTIVE',now(),now()),
                           (92,1,82,'AUTHORIZED_USER','ACTIVE',now(),now())
                    """);
        }
        factory = SubscriptionJpaTestSupport.entityManagerFactory(schema, SubscriptionJpaTestSupport.allTypes(), "validate");
        var manager = new JpaTransactionManager(factory.getObject());
        transaction = new TransactionTemplate(manager);
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        listing = new VehicleSubscriptionListingService(entityManager);
        subscriptions = new JpaRepositoryFactory(entityManager).getRepository(VehicleSubscriptionRepository.class);
        var repositories = new JpaRepositoryFactory(entityManager);
        subscriptionService = new VehicleSubscriptionService(subscriptions,
                repositories.getRepository(SubscriptionPackageRepository.class),
                repositories.getRepository(VehicleSubscriptionReceiptRepository.class),
                repositories.getRepository(PaymentRepository.class), java.time.Clock.systemUTC(), entityManager, event -> { });
    }

    @AfterEach
    void removeOnlyThisFixture() throws SQLException {
        if (factory != null) factory.destroy();
        if (schema != null) try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
            execute(administration, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void twoMatchingDriversYieldOneOrderWithCorrectTotal() {
        var page = transaction.execute(status -> listing.findAll("Matching Driver",1L,1L,null,null,PageRequest.of(0,1)));
        assertEquals(1, page.getTotalElements());
        assertEquals(1L, page.getContent().getFirst().getSubscriptionId());
        assertEquals("Monthly", page.getContent().getFirst().getTicketType());
        assertEquals("Matching Driver, Matching Driver Two", page.getContent().getFirst().getSubscriberName());
        assertEquals("30A12345", page.getContent().getFirst().getPlateNumber());
    }

    @Test
    void emptyPageRetainsRealTotalAndEscapesKeywordWildcards() {
        var page = transaction.execute(status -> listing.findAll(null,null,null,null,null,PageRequest.of(5,1)));
        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
        var literal = transaction.execute(status -> listing.findAll("%",null,null,null,null,PageRequest.of(0,10)));
        assertEquals(0, literal.getTotalElements());
    }

    @Test
    void latestOwnerProjectionAdaptsInstantAndEnumToExistingResponseContract() {
        var rows = transaction.execute(status -> subscriptions.findLatestRegisteredVehicleSubscriptions(List.of(1L)));
        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertEquals("PENDING_PAYMENT", row.getSubscriptionStatus());
        assertEquals("NEW", row.getTicketType());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), row.getValidFrom().toInstant());
    }

    @Test
    void aggregateResponsesFetchBothOrdersAndPackageSchedulesInOneStatement() {
        var statistics = factory.getObject().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        var responses = transaction.execute(status -> subscriptionService.getByIds(List.of(2L,1L)));
        assertEquals(List.of(2L,1L), responses.stream().map(response -> response.getId()).toList());
        assertTrue(responses.stream().allMatch(response -> "Monthly".equals(response.getPackageName())));
        assertEquals(1, statistics.getPrepareStatementCount());
        statistics.clear();
        assertTrue(transaction.execute(status -> subscriptionService.getByIds(List.of())).isEmpty());
        assertEquals(0, statistics.getPrepareStatementCount());
    }

    private void execute(Connection connection,String sql) throws SQLException {
        SubscriptionJpaTestSupport.execute(connection,sql);
    }
}
