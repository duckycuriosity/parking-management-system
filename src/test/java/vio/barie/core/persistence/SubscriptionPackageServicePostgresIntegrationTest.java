package vio.barie.core.persistence;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.SubscriptionPackageStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.subscription.CreateSubscriptionPackageRequest;
import vio.barie.core.dto.subscription.SubscriptionPackageSaleRequest;
import vio.barie.core.dto.subscription.SubscriptionScheduleRequest;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.SubscriptionPackageScheduleEntity;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.service.SubscriptionPackageService;
import vio.barie.core.utils.VehicleType;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Calls the real package service/JPA repositories after Flyway has removed the V54 business triggers. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class SubscriptionPackageServicePostgresIntegrationTest {
    private static final Instant FROM = Instant.parse("2026-10-09T00:00:00Z");
    private String schema;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager entityManager;
    private SubscriptionPackageRepository packages;
    private SubscriptionPackageService service;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() throws Exception {
        schema = "subscription_package_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(administration, "CREATE SCHEMA " + schema);
        }
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        SubscriptionJpaTestSupport.migrate(schema, "55");
        factory = SubscriptionJpaTestSupport.entityManagerFactory(schema, SubscriptionJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        assertNotNull(emf);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        packages = new JpaRepositoryFactory(entityManager).getRepository(SubscriptionPackageRepository.class);
        var manager = new JpaTransactionManager(emf);
        transaction = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new SubscriptionPackageService(packages, entityManager));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (SubscriptionPackageService) proxy.getProxy();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (factory != null) factory.destroy();
        if (schema != null) {
            try (Connection administration = SubscriptionJpaTestSupport.openRaw()) {
                SubscriptionJpaTestSupport.execute(administration, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void draftScheduleReplacementPublicationAndPublishedFreezeWorkAfterTriggerRemoval() {
        var request = weekly(window(7, "22:00", "02:00"), window(1, "02:00", "08:00"));
        long id = service.createDraft(request).getId();
        assertEquals(2, service.updateDraft(id, request).getSchedules().size());
        assertEquals(2L, countSchedules(id));
        assertEquals(SubscriptionPackageStatus.PUBLISHED, service.publish(id).getStatus());
        assertThrows(BusinessException.class, () -> service.updateDraft(id, request));
        assertEquals(2L, countSchedules(id));

        // Hibernate lifecycle guards also protect ordinary managed-entity changes,
        // rather than relying only on one controller route after trigger removal.
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            packages.findById(id).orElseThrow().setPriceVnd(1L);
            entityManager.flush();
        }));
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            var pack = packages.findWithSchedules(id).orElseThrow();
            pack.getSchedules().getFirst().setEndTime(LocalTime.NOON);
            entityManager.flush();
        }));
        assertEquals(300000L, service.get(id).getPriceVnd());
        assertEquals(2, service.get(id).getSchedules().size());
    }

    @Test
    void failedWeeklyValidationAndOverlapDoNotPublishOrLoseStoredSchedule() {
        long id = service.createDraft(weekly(window(7, "22:00", "02:00"))).getId();
        assertThrows(BusinessException.class, () -> service.updateDraft(id,
                weekly(window(7, "22:00", "02:00"), window(1, "01:00", "03:00"))));
        assertEquals(1, service.get(id).getSchedules().size());
        service.publish(id);
        long second = service.createDraft(request()).getId();
        assertThrows(BusinessException.class, () -> service.publish(second));
        assertEquals(SubscriptionPackageStatus.DRAFT, service.get(second).getStatus());
    }

    @Test
    void publishedAvailabilityChangesAreAllowedAndDoNotChangeFrozenTerms() {
        long first = service.createDraft(request()).getId();
        service.publish(first);
        var sale = new SubscriptionPackageSaleRequest();
        sale.setSaleEnabled(false);
        sale.setEffectiveTo(FROM.plusSeconds(86400));
        service.updateSale(first, sale);
        var secondRequest = request();
        secondRequest.setEffectiveFrom(sale.getEffectiveTo());
        long second = service.createDraft(secondRequest).getId();
        assertEquals(SubscriptionPackageStatus.PUBLISHED, service.publish(second).getStatus());
        sale.setEffectiveTo(null);
        assertThrows(BusinessException.class, () -> service.updateSale(first, sale));
        var prior = service.get(first);
        assertFalse(prior.isSaleEnabled());
        assertEquals(300000L, prior.getPriceVnd());
        assertEquals(30, prior.getDurationDays());
        assertEquals(FROM.plusSeconds(86400), prior.getEffectiveTo());
    }

    @Test
    void concurrentVersionAllocationUsesTheSameFamilyAnchor() throws Exception {
        service.createDraft(request());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return service.createDraft(request()); });
            var second = executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return service.createDraft(request()); });
            start.countDown();
            int a = first.get(20, TimeUnit.SECONDS).getVersionNo();
            int b = second.get(20, TimeUnit.SECONDS).getVersionNo();
            assertEquals(List.of(2, 3), java.util.stream.Stream.of(a, b).sorted().toList());
        }
        assertEquals(3, service.history("MONTHLY").size());
    }

    @Test
    void concurrentPublicationWithoutSqlExclusionStillRejectsOverlap() throws Exception {
        long first = service.createDraft(request()).getId();
        long second = service.createDraft(request()).getId();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return tryPublish(first); });
            var b = executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return tryPublish(second); });
            start.countDown();
            assertEquals(1, a.get(20, TimeUnit.SECONDS) + b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, service.history("MONTHLY").stream()
                .filter(p -> p.getStatus() == SubscriptionPackageStatus.PUBLISHED).count());
    }

    @Test
    void packageWriterWaitsForTheJPAFamilyLockBeforeChangingAvailability() throws Exception {
        long id = service.createDraft(request()).getId();
        service.publish(id);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc("MONTHLY").orElseThrow();
                held.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var update = new SubscriptionPackageSaleRequest(); update.setSaleEnabled(false);
            var writer = executor.submit(() -> service.updateSale(id, update));
            try {
                assertThrows(TimeoutException.class, () -> writer.get(250, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            holder.get(20, TimeUnit.SECONDS);
            assertFalse(writer.get(20, TimeUnit.SECONDS).isSaleEnabled());
        }
    }

    @Test
    void allPackageScheduleInsertUpdateAndDeletePathsHonorPublishedGuard() {
        long id = service.createDraft(weekly(window(1, "08:00", "17:00"))).getId();
        service.publish(id);
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            var pack = packages.findWithSchedules(id).orElseThrow();
            var extra = new SubscriptionPackageScheduleEntity();
            extra.setWeekday(2); extra.setStartTime(LocalTime.of(8, 0)); extra.setEndTime(LocalTime.of(17, 0));
            extra.setSubscriptionPackage(pack); entityManager.persist(extra); entityManager.flush();
        }));
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            var pack = packages.findWithSchedules(id).orElseThrow();
            pack.getSchedules().clear(); entityManager.flush();
        }));
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            entityManager.remove(packages.findById(id).orElseThrow()); entityManager.flush();
        }));
        assertEquals(1L, countSchedules(id));
    }

    private int tryPublish(long id) {
        try { service.publish(id); return 1; }
        catch (BusinessException exception) { assertEquals(409, exception.getHttpStatus()); return 0; }
    }

    private long countSchedules(long id) {
        return transaction.execute(status -> entityManager.createQuery(
                        "select count(s) from SubscriptionPackageScheduleEntity s where s.subscriptionPackage.id = :id", Long.class)
                .setParameter("id", id).getSingleResult());
    }

    private static CreateSubscriptionPackageRequest request() {
        var request = new CreateSubscriptionPackageRequest();
        request.setPackageCode("MONTHLY"); request.setName("Monthly"); request.setParkingLotId(1L);
        request.setVehicleType(VehicleType.CAR); request.setPriceVnd(300000L); request.setDurationDays(30);
        request.setCoverageMode(SubscriptionCoverageMode.ALL_DAY); request.setEffectiveFrom(FROM);
        request.setSaleEnabled(true); request.setSchedules(List.of()); return request;
    }

    private static CreateSubscriptionPackageRequest weekly(SubscriptionScheduleRequest... windows) {
        var request = request(); request.setCoverageMode(SubscriptionCoverageMode.WEEKLY);
        request.setSchedules(List.of(windows)); return request;
    }

    private static SubscriptionScheduleRequest window(int weekday, String start, String end) {
        var window = new SubscriptionScheduleRequest(); window.setWeekday(weekday);
        window.setStartTime(LocalTime.parse(start)); window.setEndTime(LocalTime.parse(end)); return window;
    }
}
