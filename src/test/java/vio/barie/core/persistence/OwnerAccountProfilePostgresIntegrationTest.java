package vio.barie.core.persistence;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountVehicleParkingCustomerResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountRequest;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.repository.*;
import vio.barie.core.service.OwnerAccountProfileService;
import vio.barie.core.service.OwnerAccountAiIdentityService;
import vio.barie.core.service.OwnerAccountService;
import vio.barie.core.service.VehicleSubscriptionService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.api.FlywayException;
import org.aopalliance.intercept.MethodInterceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real repositories/transaction boundaries; no application profile or external provider is loaded. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class OwnerAccountProfilePostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-10T02:00:00Z");
    private String schema;
    private Connection database;
    private LocalContainerEntityManagerFactoryBean factory;
    private OwnerAccountProfileService service;
    private OwnerAccountService ownerService;
    private TransactionTemplate transaction;
    private JpaRepositoryFactory repositories;
    private EntityManager entityManager;
    private JpaTransactionManager manager;
    private VehicleSubscriptionService subscriptionService;
    private OwnerAccountAiIdentityService identityService;
    private long packageId;
    private AiFaceApiClient aiClient;

    @BeforeEach
    void createIsolatedDatabaseAndServices() throws Exception {
        schema = "owner_profile_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
            execute(administration, "CREATE SCHEMA " + schema);
        }
        OwnerAccountProfileJpaTestSupport.createSchemaAndMigrate(schema);
        database = SubscriptionJpaTestSupport.open(schema);
        packageId = number("""
                INSERT INTO subscription_package
                  (package_code,name,version_no,parking_lot_id,vehicle_type,price_vnd,
                   duration_days,coverage_mode,effective_from,status)
                VALUES ('MONTHLY','Monthly car package',1,1,'CAR',500000,30,'ALL_DAY',?,'PUBLISHED')
                RETURNING id
                """, NOW.minusSeconds(60));
        factory = SubscriptionJpaTestSupport.entityManagerFactory(schema,
                SubscriptionJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        assertNotNull(emf);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        repositories = new JpaRepositoryFactory(entityManager);
        repositories.addRepositoryProxyPostProcessor((proxy, metadata) ->
                proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        manager = new JpaTransactionManager(emf);
        transaction = new TransactionTemplate(manager);
        subscriptionService = transactional(new VehicleSubscriptionService(
                repositories.getRepository(VehicleSubscriptionRepository.class),
                repositories.getRepository(SubscriptionPackageRepository.class),
                repositories.getRepository(VehicleSubscriptionReceiptRepository.class),
                repositories.getRepository(PaymentRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC), entityManager, event -> { }), manager);
        service = createProfileService(repositories, subscriptionService, entityManager, manager);
        ownerService = transactional(new OwnerAccountService(
                repositories.getRepository(OwnerAccountRepository.class),
                repositories.getRepository(VehicleRepository.class),
                repositories.getRepository(VehicleSubscriptionRepository.class),
                repositories.getRepository(VehicleCustomerRepository.class)), manager);
    }

    private OwnerAccountProfileService createProfileService(JpaRepositoryFactory repositories,
                                                            VehicleSubscriptionService subscriptions,
                                                            EntityManager entityManager,
                                                            JpaTransactionManager manager) {
        aiClient = mock(AiFaceApiClient.class);
        identityService = transactional(new OwnerAccountAiIdentityService(entityManager, manager,
                aiClient, new com.fasterxml.jackson.databind.ObjectMapper(), "https://images.example.test"), manager);
        return transactional(new OwnerAccountProfileService(
                repositories.getRepository(OwnerAccountRepository.class),
                repositories.getRepository(VehicleRepository.class),
                repositories.getRepository(ParkingCustomerRepository.class),
                repositories.getRepository(VehicleCustomerRepository.class),
                repositories.getRepository(OwnerProfileRegistrationRepository.class),
                subscriptions, identityService, entityManager), manager);
    }

    @AfterEach
    void discardOnlyThisSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (database != null) database.close();
        if (schema != null) {
            try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
                execute(administration, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void wholeRegistrationReturnsBackendQuoteAndReplaysAfterPackageCloses() throws SQLException {
        ProfileSaveRequest request = newProfile("first", "0900000001", "30A99881");
        request.getVehicles().getFirst().setSubscription(subscription(packageId));
        OwnerAccountVehicleParkingCustomerResponse first = service.createProfile(request);

        assertNull(first.getIdentityNumber());
        assertNull(first.getAiProfileId());
        assertEquals(1, first.getVehicles().size());
        var order = first.getVehicles().getFirst().getSubscription();
        assertNotNull(order);
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals(500000L, order.getPriceVnd());
        assertEquals(30L * 86400L, order.getEndsAt().getEpochSecond() - order.getStartsAt().getEpochSecond());
        assertEquals(0, number("SELECT count(*) FROM payment"));
        assertEquals(0, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        execute(database, "UPDATE subscription_package SET sale_enabled=false WHERE id=" + packageId);

        OwnerAccountVehicleParkingCustomerResponse replay = service.createProfile(request);

        assertEquals(first.getId(), replay.getId());
        assertEquals(first.getVehicles().getFirst().getId(), replay.getVehicles().getFirst().getId());
        assertEquals(order.getId(), replay.getVehicles().getFirst().getSubscription().getId());
        assertEquals(1, number("SELECT count(*) FROM owner_profile_registration"));
        assertEquals(1, number("SELECT count(*) FROM owner_account"));
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription"));
        verifyNoInteractions(aiClient);
    }

    @Test
    void sameKeyDifferentInputFailsWithoutChangingSavedGraph() throws SQLException {
        ProfileSaveRequest request = newProfile("changed", "0900000002", "30A99882");
        var first = service.createProfile(request);
        request.getOwner().setPhone("0900999999");

        BusinessException conflict = assertThrows(BusinessException.class, () -> service.createProfile(request));
        assertEquals("PROFILE_REQUEST_CONFLICT", conflict.getCode());
        assertEquals(409, conflict.getHttpStatus());

        assertEquals(1, number("SELECT count(*) FROM owner_account WHERE id=? AND phone='0900000002'", first.getId()));
        assertEquals(1, number("SELECT count(*) FROM owner_profile_registration"));
    }

    @Test
    void laterPackageRejectionRollsBackAllCreatedPeopleVehiclesRelationsAndEarlierOrder() throws SQLException {
        ProfileSaveRequest request = newProfile("rollback", "0900000003", "30A99883");
        var first = request.getVehicles().getFirst();
        first.setSubscription(subscription(packageId));
        var second = newVehicle("car-2", "30A99884");
        second.setCustomers(List.of(newCustomer("driver-1")));
        second.setSubscription(subscription(Long.MAX_VALUE));
        request.setVehicles(List.of(first, second));

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.createProfile(request));
        assertEquals(404, rejected.getHttpStatus());

        assertEquals(0, number("SELECT count(*) FROM owner_account"));
        assertEquals(0, number("SELECT count(*) FROM vehicle WHERE owner_id IS NOT NULL"));
        assertEquals(0, number("SELECT count(*) FROM parking_customer"));
        assertEquals(0, number("SELECT count(*) FROM vehicle_customer"));
        assertEquals(0, number("SELECT count(*) FROM vehicle_subscription"));
        assertEquals(0, number("SELECT count(*) FROM owner_profile_registration"));
        second.setSubscription(subscription(packageId));
        var corrected = service.createProfile(request);
        assertEquals(2, corrected.getVehicles().size());
        assertEquals(2, number("SELECT count(*) FROM vehicle_subscription"));
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
    }

    @Test
    void ownerAsDriverIsExplicitlyOnePersonAcrossVehiclesAndReusesConfirmedAiIdentity() throws SQLException {
        ProfileSaveRequest request = newProfile("owner-driver", "0900000004", "30A99885");
        var driver = ownerDriver();
        request.getVehicles().getFirst().setCustomers(List.of(driver));
        var second = newVehicle("car-2", "30A99886");
        second.setCustomers(List.of(ownerDriver()));
        request.setVehicles(List.of(request.getVehicles().getFirst(), second));
        var first = service.createProfile(request);

        assertNotNull(first.getDriverCustomerId());
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(2, number("SELECT count(*) FROM vehicle_customer WHERE customer_id=?", first.getDriverCustomerId()));
        assertEquals(1, number("SELECT count(*) FROM parking_customer WHERE id=? AND owner_id=?",
                first.getDriverCustomerId(), first.getId()));
        var replay = service.createProfile(request);
        assertEquals(first.getDriverCustomerId(), replay.getDriverCustomerId());
        assertEquals(first.getVehicles().getFirst().getId(), replay.getVehicles().getFirst().getId());
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(2, number("SELECT count(*) FROM vehicle_customer"));
        execute(database, "UPDATE owner_account SET ai_profile_id=7001,ai_identity_id='owner-ai',ai_sync_state='SYNCED' WHERE id=" + first.getId());
        execute(database, "UPDATE parking_customer SET ai_profile_id=7001,ai_identity_id='owner-ai',ai_sync_state='SYNCED' WHERE id=" + first.getDriverCustomerId());
        var add = selectOwner("owner-driver-next", first.getId());
        var third = newVehicle("car-3", "30A99887");
        third.setCustomers(List.of(ownerDriver()));
        add.setVehicles(List.of(third));

        var next = service.createProfile(add);

        assertEquals(first.getDriverCustomerId(), next.getDriverCustomerId());
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(7001L, number("SELECT ai_profile_id FROM parking_customer WHERE id=?", first.getDriverCustomerId()));
        assertEquals(3, number("SELECT count(*) FROM vehicle_customer"));
        verifyNoInteractions(aiClient);
    }

    @Test
    void explicitDraftPersonKeySharesOneDriverButConflictingDraftDataRollsBack() throws SQLException {
        ProfileSaveRequest shared = newProfile("shared-driver", "0900000005", "30A99888");
        var second = newVehicle("car-2", "30A99889");
        second.setCustomers(List.of(newCustomer("driver-1")));
        shared.setVehicles(List.of(shared.getVehicles().getFirst(), second));

        service.createProfile(shared);

        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(2, number("SELECT count(*) FROM vehicle_customer"));
        ProfileSaveRequest conflict = newProfile("conflicting-driver", "0900000006", "30A99890");
        var conflictSecond = newVehicle("car-2", "30A99891");
        var different = newCustomer("driver-1");
        different.setName("Different person");
        conflictSecond.setCustomers(List.of(different));
        conflict.setVehicles(List.of(conflict.getVehicles().getFirst(), conflictSecond));

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.createProfile(conflict));
        assertEquals(400, rejected.getHttpStatus());

        assertEquals(1, number("SELECT count(*) FROM owner_account"));
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(2, number("SELECT count(*) FROM vehicle_customer"));
    }

    @Test
    void existingVehicleAndCustomerMustBelongToSelectedOwner() throws SQLException {
        var first = service.createProfile(newProfile("scope-a", "0900000007", "30A99892"));
        var second = service.createProfile(newProfile("scope-b", "0900000008", "30A99893"));
        ProfileSaveRequest foreignVehicle = selectOwner("foreign-vehicle", first.getId());
        var vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setId(second.getVehicles().getFirst().getId());
        foreignVehicle.setVehicles(List.of(vehicle));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.createProfile(foreignVehicle)).getHttpStatus());

        ProfileSaveRequest foreignCustomer = selectOwner("foreign-customer", first.getId());
        var ownVehicle = new ProfileSaveRequest.Vehicle();
        ownVehicle.setId(first.getVehicles().getFirst().getId());
        var customer = new ProfileSaveRequest.Customer();
        customer.setId(second.getCustomers().getFirst().getId());
        customer.setRelationType("AUTHORIZED_USER");
        ownVehicle.setCustomers(List.of(customer));
        foreignCustomer.setVehicles(List.of(ownVehicle));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.createProfile(foreignCustomer)).getHttpStatus());

        assertEquals(2, number("SELECT count(*) FROM owner_profile_registration"));
        assertEquals(2, number("SELECT count(*) FROM vehicle_customer"));
        assertEquals(1, number("SELECT count(*) FROM vehicle WHERE id=? AND owner_id=?",
                second.getVehicles().getFirst().getId(), second.getId()));
    }

    @Test
    void selectingOneExistingVehicleDoesNotDeactivateOmittedPeopleOrRelations() throws SQLException {
        ProfileSaveRequest registration = newProfile("omission-first", "0900000009", "30A99894");
        var second = newVehicle("car-2", "30A99895");
        var other = newCustomer("driver-2");
        other.setName("Another driver");
        second.setCustomers(List.of(other));
        registration.setVehicles(List.of(registration.getVehicles().getFirst(), second));
        var original = service.createProfile(registration);
        var request = selectOwner("omission-next", original.getId());
        var selected = new ProfileSaveRequest.Vehicle();
        selected.setId(original.getVehicles().getFirst().getId());
        selected.setCustomers(List.of());
        request.setVehicles(List.of(selected));

        service.createProfile(request);

        assertEquals(2, number("SELECT count(*) FROM parking_customer WHERE status='ACTIVE'"));
        assertEquals(2, number("SELECT count(*) FROM vehicle_customer WHERE status='ACTIVE'"));
        assertEquals(2, number("SELECT count(*) FROM vehicle WHERE owner_id=?", original.getId()));
    }

    @Test
    void competingSameKeyRequestsCommitOnlyOneCompleteGraphWhenRaceLoserRetries() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return registerWithOneConcurrentRetry(newProfile("race", "0900000010", "30A99896"));
            });
            var second = threads.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return registerWithOneConcurrentRetry(newProfile("race", "0900000010", "30A99896"));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var one = first.get(30, TimeUnit.SECONDS);
            var two = second.get(30, TimeUnit.SECONDS);
            assertEquals(one.getId(), two.getId());
            assertEquals(one.getVehicles().getFirst().getId(), two.getVehicles().getFirst().getId());
            assertEquals(one.getCustomers().getFirst().getId(), two.getCustomers().getFirst().getId());
        }
        assertEquals(1, number("SELECT count(*) FROM owner_account"));
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_customer"));
        assertEquals(1, number("SELECT count(*) FROM owner_profile_registration"));
    }

    @Test
    void migrationRefusesAmbiguousLegacyAiIdentityAndPreservesBothPeople() throws Exception {
        // PostgreSQL identifiers are limited to 63 bytes; use an independent short name.
        String duplicateSchema = "owner_ai_duplicate_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
            execute(administration, "CREATE SCHEMA " + duplicateSchema);
        }
        try {
            FlywayException failure = assertThrows(FlywayException.class,
                    () -> OwnerAccountProfileJpaTestSupport.createSchemaAndMigrate(duplicateSchema, connection ->
                            execute(connection, """
                                    INSERT INTO parking_customer (username, ai_profile_id, ai_identity_id, status)
                                    VALUES ('Historical person A', 88, 'first-provider-id', 'ACTIVE'),
                                           ('Historical person B', 88, 'second-provider-id', 'ACTIVE')
                                    """)));
            StringBuilder diagnostic = new StringBuilder();
            SQLException providerFailure = null;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                diagnostic.append(cause.getMessage()).append('\n');
                if (cause instanceof SQLException sql) providerFailure = sql;
            }
            assertTrue(diagnostic.toString().contains("duplicate parking_customer.ai_profile_id"),
                    diagnostic.toString());
            assertNotNull(providerFailure, "Expected the actual PostgreSQL migration preflight failure");
            assertEquals("P0001", providerFailure.getSQLState());
            try (Connection legacy = SubscriptionJpaTestSupport.open(duplicateSchema);
                 PreparedStatement statement = legacy.prepareStatement("""
                         SELECT count(*), count(DISTINCT ai_identity_id)
                         FROM parking_customer WHERE ai_profile_id=88
                         """); var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(1));
                assertEquals(2, rows.getLong(2));
            }
        } finally {
            try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
                execute(administration, "DROP SCHEMA IF EXISTS " + duplicateSchema + " CASCADE");
            }
        }
    }

    private OwnerAccountVehicleParkingCustomerResponse registerWithOneConcurrentRetry(ProfileSaveRequest request) {
        try {
            return service.createProfile(request);
        } catch (BusinessException concurrent) {
            assertEquals("PROFILE_REQUEST_CONCURRENT", concurrent.getCode());
            assertEquals(409, concurrent.getHttpStatus());
            // Unique-key arbitration completes only once the winner has committed.
            // The losing transaction is rolled back before this fresh request replays.
            return service.createProfile(request);
        }
    }

    @Test
    void differentRequestKeysRacingForOnePhoneReturnOneConflictAndNoPartialLoserGraph() throws Exception {
        CountDownLatch bothCheckedPhone = new CountDownLatch(2);
        var repositoryProxy = new ProxyFactory(repositories.getRepository(OwnerAccountRepository.class));
        repositoryProxy.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("existsByPhone")
                    && invocation.getArguments()[0].equals("0900000020") && Boolean.FALSE.equals(result)) {
                // Both actual SELECTs must observe no owner before either INSERT can run.
                // This reaches PostgreSQL uniqueness arbitration rather than the precheck.
                bothCheckedPhone.countDown();
                assertTrue(bothCheckedPhone.await(10, TimeUnit.SECONDS));
            }
            return result;
        });
        var guardedOwners = (OwnerAccountRepository) repositoryProxy.getProxy();
        OwnerAccountProfileService racing = transactional(new OwnerAccountProfileService(
                guardedOwners, repositories.getRepository(VehicleRepository.class),
                repositories.getRepository(ParkingCustomerRepository.class),
                repositories.getRepository(VehicleCustomerRepository.class),
                repositories.getRepository(OwnerProfileRegistrationRepository.class),
                subscriptionService, identityService, entityManager), manager);
        List<Object> outcomes;
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> registerOrConflict(racing,
                    newProfile("phone-race-a", "0900000020", "30A99897")));
            var second = threads.submit(() -> registerOrConflict(racing,
                    newProfile("phone-race-b", "0900000020", "30A99898")));
            outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, outcomes.stream().filter(OwnerAccountVehicleParkingCustomerResponse.class::isInstance).count());
        var conflict = outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast).findFirst().orElseThrow();
        assertEquals(409, conflict.getHttpStatus());
        assertEquals("OWNER_PHONE_CONFLICT", conflict.getCode());
        assertEquals(1, number("SELECT count(*) FROM owner_account"));
        assertEquals(1, number("SELECT count(*) FROM owner_profile_registration"));
        assertEquals(1, number("SELECT count(*) FROM vehicle WHERE owner_id IS NOT NULL"));
        assertEquals(1, number("SELECT count(*) FROM parking_customer"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_customer"));
    }

    @Test
    void deletingOwnerWithRegistrationHistoryReturnsConflictAndKeepsEntireGraph() throws SQLException {
        var registration = newProfile("protected-delete", "0900000021", "30A99899");
        registration.getVehicles().getFirst().setSubscription(subscription(packageId));
        var saved = service.createProfile(registration);

        var conflict = assertThrows(BusinessException.class, () -> ownerService.delete(saved.getId()));

        assertEquals(409, conflict.getHttpStatus());
        assertEquals("OWNER_HAS_LINKED_DATA", conflict.getCode());
        assertEquals(1, number("SELECT count(*) FROM owner_account WHERE id=? AND status='ACTIVE'", saved.getId()));
        assertEquals(1, number("SELECT count(*) FROM vehicle WHERE owner_id=?", saved.getId()));
        assertEquals(1, number("SELECT count(*) FROM parking_customer WHERE owner_id=?", saved.getId()));
        assertEquals(1, number("SELECT count(*) FROM vehicle_customer"));
        assertEquals(1, number("SELECT count(*) FROM owner_profile_registration"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription"));
    }

    @Test
    void deletingAnUnreferencedLegacyOwnerStillSucceeds() throws SQLException {
        OwnerAccountRequest request = new OwnerAccountRequest();
        request.setFullName("Unreferenced owner");
        request.setPhone("0900000022");
        var saved = ownerService.create(request);
        assertEquals(0, number("SELECT count(*) FROM owner_profile_registration"));

        ownerService.delete(saved.getId());

        assertEquals(0, number("SELECT count(*) FROM owner_account"));
    }

    private Object registerOrConflict(OwnerAccountProfileService racing, ProfileSaveRequest request) {
        try {
            return racing.createProfile(request);
        } catch (BusinessException failure) {
            return failure;
        }
    }

    private ProfileSaveRequest newProfile(String key, String phone, String plate) {
        var request = new ProfileSaveRequest();
        request.setRequestKey(key);
        var owner = new ProfileSaveRequest.Owner();
        owner.setFullName("Owner " + key);
        owner.setPhone(phone);
        request.setOwner(owner);
        var vehicle = newVehicle("car-1", plate);
        vehicle.setCustomers(List.of(newCustomer("driver-1")));
        request.setVehicles(List.of(vehicle));
        return request;
    }

    private ProfileSaveRequest selectOwner(String key, Long id) {
        var request = new ProfileSaveRequest();
        request.setRequestKey(key);
        var owner = new ProfileSaveRequest.Owner();
        owner.setId(id);
        request.setOwner(owner);
        return request;
    }

    private ProfileSaveRequest.Vehicle newVehicle(String clientId, String plate) {
        var vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setClientId(clientId);
        vehicle.setPlate(plate);
        vehicle.setVehicleType("CAR");
        return vehicle;
    }

    private ProfileSaveRequest.Customer newCustomer(String clientId) {
        var customer = new ProfileSaveRequest.Customer();
        customer.setClientId(clientId);
        customer.setName("Driver one");
        customer.setRelationType("AUTHORIZED_USER");
        return customer;
    }

    private ProfileSaveRequest.Customer ownerDriver() {
        var customer = new ProfileSaveRequest.Customer();
        customer.setOwnerAsDriver(true);
        customer.setRelationType("OWNER");
        return customer;
    }

    private ProfileSaveRequest.Subscription subscription(long id) {
        var order = new ProfileSaveRequest.Subscription();
        order.setPackageId(id);
        order.setParkingLotId(1L);
        order.setStartsAt(NOW.atOffset(ZoneOffset.UTC));
        order.setPaymentDeadlineAt(NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        return order;
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target, JpaTransactionManager manager) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private long number(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof Instant instant) value = Timestamp.from(instant);
                statement.setObject(i + 1, value);
            }
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        SubscriptionJpaTestSupport.execute(connection, sql);
    }
}
