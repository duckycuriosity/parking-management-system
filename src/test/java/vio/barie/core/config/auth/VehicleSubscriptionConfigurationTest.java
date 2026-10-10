package vio.barie.core.config.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loads config resources only; no application context, datasource, JWT issuer or network client. */
class VehicleSubscriptionConfigurationTest {

    @Test
    void devPropertiesDisableSubscriptionAuthAndKeepExistingYamlSettings() {
        StandardEnvironment environment = load("dev", null);

        assertEquals(Boolean.FALSE,
                environment.getProperty("app.vehicle-subscription.auth-enabled", Boolean.class));
        assertEquals(Boolean.FALSE,
                environment.getProperty("app.fee-policy.auth-enabled", Boolean.class));
        assertExistingYamlLoaded(environment, "dev");
    }

    @Test
    void prodPropertiesRequireSubscriptionAuthAndKeepExistingYamlSettings() {
        StandardEnvironment environment = load("prod", null);

        assertEquals(Boolean.TRUE,
                environment.getProperty("app.vehicle-subscription.auth-enabled", Boolean.class));
        assertEquals(Boolean.TRUE,
                environment.getProperty("app.fee-policy.auth-enabled", Boolean.class));
        assertExistingYamlLoaded(environment, "prod");
    }

    @Test
    void environmentOverrideCanDisableAuthOnProdWithoutReplacingYaml() {
        StandardEnvironment environment = load("prod", "false");

        assertEquals(Boolean.FALSE,
                environment.getProperty("app.vehicle-subscription.auth-enabled", Boolean.class));
        assertEquals(Boolean.TRUE,
                environment.getProperty("app.fee-policy.auth-enabled", Boolean.class));
        assertExistingYamlLoaded(environment, "prod");
    }

    private static StandardEnvironment load(String profile, String override) {
        StandardEnvironment environment = new StandardEnvironment();
        // Isolate from the machine's settings. The explicit classpath location never
        // follows working-directory configs or starts the application's service beans.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("config-loading-test",
                Map.of("spring.config.location", "classpath:/", "spring.profiles.active", profile)));
        if (override != null) {
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-environment",
                    Map.of("APP_VEHICLE_SUBSCRIPTION_AUTH_ENABLED", override)));
        }
        environment.setActiveProfiles(profile);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    private static void assertExistingYamlLoaded(StandardEnvironment environment, String profile) {
        // Check names/presence only: credentials and connection values never appear in assertions.
        assertTrue(environment.containsProperty("spring.datasource.url"));
        assertTrue(environment.containsProperty("spring.datasource.username"));
        assertTrue(environment.containsProperty("spring.datasource.password"));
        assertTrue(environment.containsProperty("payment.method"));
        assertTrue(StreamSupport.stream(environment.getPropertySources().spliterator(), false)
                .anyMatch(source -> source.getName().contains("application-" + profile + ".yml")));
        assertTrue(StreamSupport.stream(environment.getPropertySources().spliterator(), false)
                .anyMatch(source -> source.getName().contains("application-" + profile + ".properties")));
    }
}
