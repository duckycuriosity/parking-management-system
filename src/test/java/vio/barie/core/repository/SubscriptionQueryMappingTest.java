package vio.barie.core.repository;

import jakarta.persistence.Entity;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import vio.barie.core.entity.VehicleEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Checks Hibernate/Spring Data query mappings without a database connection or schema changes. */
class SubscriptionQueryMappingTest {
    private static StandardServiceRegistry registry;
    private static Metadata metadata;
    private static SessionFactoryImplementor factory;

    @BeforeAll
    static void buildMappings() throws ClassNotFoundException {
        registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting(AvailableSettings.ALLOW_METADATA_ON_BOOT, false)
                .applySetting(AvailableSettings.CONNECTION_PROVIDER, UserSuppliedConnectionProviderImpl.class)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (var candidate : scanner.findCandidateComponents("vio.barie.core.entity")) {
            sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
        }
        metadata = sources.buildMetadata();
        factory = (SessionFactoryImplementor) metadata.buildSessionFactory();
    }

    @AfterAll
    static void closeMappings() {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void subscriptionListingAndGeneratedCountValidateWithRealHibernate() {
        try (var entityManager = factory.createEntityManager()) {
            assertDoesNotThrow(() -> new JpaRepositoryFactory(entityManager)
                    .getRepository(VehicleSubscriptionRepository.class));
        }
    }

    @Test
    void ownerVehicleCountsAndDetailProjectionsValidateWithRealHibernate() {
        try (var entityManager = factory.createEntityManager()) {
            assertDoesNotThrow(() -> new JpaRepositoryFactory(entityManager).getRepository(VehicleRepository.class));
        }
    }

    @Test
    void literalBatchAndEligibilityQueriesInSubscriptionServiceResolveMappedAttributes() throws Exception {
        String source = Files.readString(Path.of("src/main/java/vio/barie/core/service/VehicleSubscriptionService.java"));
        Pattern queryLiteral = Pattern.compile(
                "(?:createQuery|labels)\\s*\\(\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")");
        var matcher = queryLiteral.matcher(source);
        int queryCount = 0;
        while (matcher.find()) {
            String hql = matcher.group(1) != null ? matcher.group(1).strip() : matcher.group(2);
            assertDoesNotThrow(() -> factory.getQueryEngine().getHqlTranslator().translate(hql, null), hql);
            queryCount++;
        }
        assertTrue(queryCount >= 5, "Read actual list batches and eligibility queries from the service");
    }

    @Test
    void vehicleUsesOneInheritedDatabaseIdentifier() {
        var vehicle = metadata.getEntityBinding(VehicleEntity.class.getName());
        assertEquals("id", vehicle.getIdentifierProperty().getName());
        assertEquals(1, vehicle.getIdentifier().getColumnSpan());
        assertEquals("id", vehicle.getIdentifier().getColumns().getFirst().getName());
        assertTrue(java.util.Arrays.stream(VehicleEntity.class.getDeclaredFields())
                .noneMatch(field -> field.isAnnotationPresent(jakarta.persistence.Id.class)));
        VehicleEntity value = new VehicleEntity();
        value.setId(17L);
        assertEquals(17L, value.getId());
    }
}
