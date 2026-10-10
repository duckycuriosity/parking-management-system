package vio.barie.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.service.OwnerAccountAiIdentityService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual production V58 and JPA mappings; SQL only observes disposable committed test data. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class OwnerAccountAiIdentityPostgresIntegrationTest {
    private String schema;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private OwnerAccountAiIdentityService service;
    private AiFaceApiClient client;
    private long ownerId;
    private long customerId;
    private final List<MultipartFile> images = List.of(new MockMultipartFile("images", "face.jpg",
            "image/jpeg", new byte[]{1, 2, 3}));

    @BeforeEach
    void setup() throws Exception {
        schema = "owner_ai_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(connection, "CREATE SCHEMA " + schema);
        }
        OwnerAccountProfileJpaTestSupport.createSchemaAndMigrate(schema);
        factory = SubscriptionJpaTestSupport.entityManagerFactory(schema, SubscriptionJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        var manager = new JpaTransactionManager(emf);
        transactions = new TransactionTemplate(manager);
        client = mock(AiFaceApiClient.class);
        ProxyFactory proxy = new ProxyFactory(new OwnerAccountAiIdentityService(entityManager, manager, client,
                new ObjectMapper(), "https://images.example"));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (OwnerAccountAiIdentityService) proxy.getProxy();
        transactions.executeWithoutResult(status -> {
            OwnerAccountEntity owner = new OwnerAccountEntity();
            owner.setFullName("Owner");
            owner.setPhone("0900000000");
            owner.setStatus("ACTIVE");
            entityManager.persist(owner);
            ownerId = owner.getId();
            ParkingCustomerEntity customer = new ParkingCustomerEntity();
            customer.setOwnerId(ownerId);
            customer.setUsername("Driver");
            customer.setStatus("ACTIVE");
            entityManager.persist(customer);
            customerId = customer.getId();
        });
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (factory != null) factory.destroy();
        if (schema != null) {
            try (Connection connection = SubscriptionJpaTestSupport.openRaw()) {
                SubscriptionJpaTestSupport.execute(connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void ownerDriverClaimCommitsBeforeIoAndActualBigintMappingsRoundTrip() {
        service.linkOwnerAsDriver(ownerId, customerId);
        when(client.createProfile(eq("Owner"), anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(text("SELECT ai_sync_state FROM owner_account WHERE id = " + ownerId)).isEqualTo("IN_PROGRESS");
            // A distinct connection can lock the owner immediately while the external call runs.
            try (Connection connection = SubscriptionJpaTestSupport.open(schema)) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement(); var locked = statement.executeQuery(
                        "SELECT id FROM owner_account WHERE id = " + ownerId + " FOR UPDATE NOWAIT")) {
                    assertThat(locked.next()).isTrue();
                }
                connection.rollback();
            }
            return profile(88);
        });
        var result = service.syncDriver(ownerId, customerId, images);
        assertThat(result.state()).isEqualTo("SYNCED");
        assertThat(text("SELECT ai_profile_id::text FROM owner_account WHERE id = " + ownerId)).isEqualTo("88");
        assertThat(text("SELECT ai_profile_id::text FROM parking_customer WHERE id = " + customerId)).isEqualTo("88");
        assertThat(text("SELECT pg_typeof(ai_profile_id)::text FROM parking_customer WHERE id = " + customerId)).isEqualTo("bigint");
        var persisted = transactions.execute(status -> entityManager.find(ParkingCustomerEntity.class, customerId));
        assertThat(persisted.getAiProfileId()).isEqualTo(88);
        assertThat(persisted.getAiIdentityId()).isEqualTo("provider-uuid");
    }

    @Test
    void concurrentSyncCannotCreateSecondProviderProfile() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.createProfile(anyString(), anyList())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Release timeout");
            return profile(88);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.syncOwner(ownerId, images));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> service.syncOwner(ownerId, images))
                        .isInstanceOf(ServiceException.class).hasMessageContaining("in progress");
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).aiProfileId()).isEqualTo(88L);
        }
        verify(client, times(1)).createProfile(anyString(), anyList());
    }

    @Test
    void mqttCollisionPreservesReceiptOfProviderIdWithoutAttachingDifferentPerson() {
        service.linkOwnerAsDriver(ownerId, customerId);
        when(client.createProfile(anyString(), anyList())).thenAnswer(call -> {
            transactions.executeWithoutResult(status -> {
                ParkingCustomerEntity inferred = new ParkingCustomerEntity();
                inferred.setAiProfileId(88);
                inferred.setAiIdentityId("temp_customer_live_event");
                inferred.setUsername("Inferred");
                inferred.setAiSyncState("SYNCED");
                entityManager.persist(inferred);
            });
            return profile(88);
        });
        assertThatThrownBy(() -> service.syncOwner(ownerId, images))
                .isInstanceOf(ServiceException.class).hasMessageContaining("different PMS person");
        assertThat(text("SELECT ai_sync_state FROM owner_account WHERE id = " + ownerId))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(text("SELECT ai_sync_profile_id::text FROM owner_account WHERE id = " + ownerId)).isEqualTo("88");
        assertThat(text("SELECT ai_profile_id::text FROM owner_account WHERE id = " + ownerId)).isNull();
        assertThat(text("SELECT ai_profile_id::text FROM parking_customer WHERE id = " + customerId)).isNull();
        assertThat(text("SELECT count(*)::text FROM parking_customer WHERE ai_profile_id = 88")).isEqualTo("1");
    }

    @Test
    void actualConcurrentImageChangeRejectsStaleResponseAndReusesKnownIdForRetry() {
        when(client.createProfile(anyString(), anyList())).thenAnswer(call -> {
            transactions.executeWithoutResult(status -> {
                OwnerAccountEntity owner = entityManager.find(OwnerAccountEntity.class, ownerId);
                owner.setImageUrl("https://local.example/new.jpg");
            });
            return profile(88);
        });
        var first = service.syncOwner(ownerId, images);
        assertThat(first.state()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(text("SELECT ai_profile_id::text FROM owner_account WHERE id = " + ownerId)).isNull();
        assertThat(text("SELECT ai_sync_profile_id::text FROM owner_account WHERE id = " + ownerId)).isEqualTo("88");
        when(client.updateProfile(eq(88), anyString(), anyList())).thenReturn(profile(88));
        assertThat(service.syncOwner(ownerId, images).state()).isEqualTo("SYNCED");
        verify(client, times(1)).createProfile(anyString(), anyList());
        verify(client).updateProfile(eq(88), anyString(), anyList());
    }

    @Test
    void rejectsInheritedTransactionAndDatabaseUniqueIndexPreventsDuplicateCustomers() {
        assertThatThrownBy(() -> transactions.execute(status -> service.syncOwner(ownerId, images)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(client);
        transactions.executeWithoutResult(status -> {
            ParkingCustomerEntity customer = entityManager.find(ParkingCustomerEntity.class, customerId);
            customer.setAiProfileId(88);
        });
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            ParkingCustomerEntity duplicate = new ParkingCustomerEntity();
            duplicate.setAiProfileId(88);
            entityManager.persist(duplicate);
        })).isInstanceOf(RuntimeException.class);
        assertThat(text("SELECT count(*)::text FROM parking_customer WHERE ai_profile_id = 88")).isEqualTo("1");
    }

    private AiProfileResponse profile(int id) {
        AiProfileResponse response = new AiProfileResponse();
        response.setId(id);
        response.setUuid("provider-uuid");
        response.setImages(List.of(Map.of("accessUrl", "http://core-ai/face.jpg")));
        return response;
    }

    private String text(String sql) {
        try (Connection connection = SubscriptionJpaTestSupport.open(schema);
             var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }
}
