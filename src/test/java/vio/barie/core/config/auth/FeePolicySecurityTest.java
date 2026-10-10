package vio.barie.core.config.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.controller.FeePolicyController;
import vio.barie.core.config.exception.GlobalExceptionHandler;
import vio.barie.core.config.filter.UserContextFilter;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.service.FeePolicyService;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Isolated HTTP/security checks: no application DB, issuer network or Keycloak client. */
class FeePolicySecurityTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FeePolicyService service;

    @BeforeEach
    void setUp() {
        configure(true, true);
    }

    private void configure(Boolean authEnabled, boolean includeDecoder) {
        configure(authEnabled, includeDecoder, null);
    }

    private void configure(Boolean authEnabled, boolean includeDecoder, String environmentOverride) {
        if (context != null) {
            context.close();
        }
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // Keep this fixture focused on fee policy; subscription security has its own tests.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "other-module-test", Map.of("app.vehicle-subscription.auth-enabled", "false")));
        if (authEnabled != null) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "fee-policy-test", Map.of("app.fee-policy.auth-enabled", authEnabled.toString())));
        }
        if (environmentOverride != null) {
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "fee-policy-test-environment", Map.of("APP_FEE_POLICY_AUTH_ENABLED", environmentOverride)));
        }
        context.register(TestConfiguration.class);
        if (includeDecoder) {
            context.register(DecoderConfiguration.class);
        }
        context.refresh();
        service = context.getBean(FeePolicyService.class);

        if (includeDecoder) {
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            when(decoder.decode("viewer")).thenReturn(token("ROLE_ORG_VIEW"));
            when(decoder.decode("editor")).thenReturn(token("ROLE_ORG_EDIT"));
            when(decoder.decode("wrong-role")).thenReturn(token("ROLE_UNKNOWN"));
            when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid test token"));
        }
        JwtAuthConverter converter = context.getBean(JwtAuthConverter.class);
        when(converter.convert(any(Jwt.class))).thenAnswer(invocation -> {
            Jwt jwt = invocation.getArgument(0);
            return new UsernamePasswordAuthenticationToken(jwt.getSubject(), null,
                    List.of(new SimpleGrantedAuthority(jwt.getClaimAsString("authority"))));
        });
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousFeePolicyRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/fee-policies/current")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedUserWithoutViewAuthorityIsForbidden() throws Exception {
        mvc.perform(get("/api/fee-policies/current")
                        .header("Authorization", "Bearer wrong-role")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void viewerCanReadCurrentPolicy() throws Exception {
        when(service.findCurrent(33L, VehicleType.CAR)).thenReturn(response());
        mvc.perform(get("/api/fee-policies/current")
                        .header("Authorization", "Bearer viewer")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));
        verify(service).findCurrent(33L, VehicleType.CAR);
    }

    @Test
    void viewerCannotPublishPrices() throws Exception {
        mvc.perform(post("/api/fee-policies/apply")
                        .header("Authorization", "Bearer viewer")
                        .contentType("application/json").content(applyJson()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void editorCanPublishPrices() throws Exception {
        when(service.apply(any())).thenReturn(response());
        mvc.perform(post("/api/fee-policies/apply")
                        .header("Authorization", "Bearer editor")
                        .contentType("application/json").content(applyJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));
        verify(service).apply(any());
    }

    @Test
    void invalidQueryIdentifierReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/fee-policies/current")
                        .header("Authorization", "Bearer viewer")
                        .param("parkingLotId", "0").param("vehicleType", "CAR"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void unrelatedLegacyRouteDoesNotBecomeUnauthorized() throws Exception {
        // No controller exists here; 404 proves it passed the unchanged permissive chain.
        mvc.perform(get("/legacy-not-mapped")).andExpect(status().isNotFound());
    }

    @Test
    void enabledAuthenticationRejectsInvalidJwt() throws Exception {
        mvc.perform(get("/api/fee-policies/current")
                        .header("Authorization", "Bearer invalid")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isUnauthorized());
        verify(context.getBean(JwtDecoder.class)).decode("invalid");
        verifyNoInteractions(context.getBean(JwtAuthConverter.class), service);
    }

    @Test
    void missingFlagDefaultsToAuthenticatedAccess() throws Exception {
        configure(null, true);
        assertTrue(context.getBean(FeePolicySecuritySettings.class).isAuthEnabled());
        mvc.perform(get("/api/fee-policies/current")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void disabledAuthenticationAllowsAllReadRoutesWithoutDecoder() throws Exception {
        configure(false, false);
        assertFalse(context.containsBean("jwtDecoder"));
        assertFalse(context.getBean(FeePolicySecuritySettings.class).isAuthEnabled());
        Instant at = Instant.parse("2026-10-09T02:00:00Z");
        when(service.findCurrent(33L, VehicleType.CAR)).thenReturn(response());
        when(service.findAt(33L, VehicleType.CAR, at)).thenReturn(response());
        when(service.findHistory(33L, VehicleType.CAR)).thenReturn(List.of(response()));
        when(service.getById(101)).thenReturn(response());

        mvc.perform(get("/api/fee-policies/current")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(101));
        mvc.perform(get("/api/fee-policies/at")
                        .param("parkingLotId", "33").param("vehicleType", "CAR")
                        .param("at", at.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(101));
        mvc.perform(get("/api/fee-policies/history")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(101));
        mvc.perform(get("/api/fee-policies/101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(101));

        verify(service).findCurrent(33L, VehicleType.CAR);
        verify(service).findAt(33L, VehicleType.CAR, at);
        verify(service).findHistory(33L, VehicleType.CAR);
        verify(service).getById(101);
        verifyNoInteractions(context.getBean(JwtAuthConverter.class));
    }

    @Test
    void disabledAuthenticationAllowsAnonymousApplyWithoutDecoder() throws Exception {
        configure(false, false);
        when(service.apply(any())).thenReturn(response());
        mvc.perform(post("/api/fee-policies/apply")
                        .contentType("application/json").content(applyJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(101));
        verify(service).apply(any());
        verifyNoInteractions(context.getBean(JwtAuthConverter.class));
    }

    @Test
    void disabledAuthenticationIgnoresStaleBearerTokens() throws Exception {
        configure(false, true);
        when(service.findCurrent(33L, VehicleType.CAR)).thenReturn(response());
        when(service.apply(any())).thenReturn(response());
        mvc.perform(get("/api/fee-policies/current")
                        .header("Authorization", "Bearer invalid")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/fee-policies/apply")
                        .header("Authorization", "Bearer invalid")
                        .contentType("application/json").content(applyJson()))
                .andExpect(status().isOk());
        verifyNoInteractions(context.getBean(JwtDecoder.class), context.getBean(JwtAuthConverter.class));
    }

    @Test
    void disabledAuthenticationStillValidatesQueryAndBody() throws Exception {
        configure(false, false);
        mvc.perform(get("/api/fee-policies/current")
                        .param("parkingLotId", "0").param("vehicleType", "CAR"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/fee-policies/apply")
                        .contentType("application/json")
                        .content("""
                                {"parkingLotId":33,"vehicleType":"CAR","name":"",
                                 "calculationMethod":"DAILY_ENTRY","dailyPriceVnd":5000}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, context.getBean(JwtAuthConverter.class));
    }

    @Test
    void disabledAuthenticationPreservesLegacyFallback() throws Exception {
        configure(false, false);
        mvc.perform(get("/legacy-not-mapped")).andExpect(status().isNotFound());
    }

    @Test
    void environmentVariableOverridesConfiguredAuthenticationFlag() throws Exception {
        configure(true, false, "false");
        assertFalse(context.getBean(FeePolicySecuritySettings.class).isAuthEnabled());
        when(service.findCurrent(33L, VehicleType.CAR)).thenReturn(response());
        mvc.perform(get("/api/fee-policies/current")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isOk());
        verifyNoInteractions(context.getBean(JwtAuthConverter.class));
    }

    private static FeePolicyResponse response() {
        return FeePolicyResponse.builder().id(101).parkingLotId(33L)
                .vehicleType(VehicleType.CAR).name("Visitor tariff").versionNo(1)
                .calculationMethod(FeeCalculationMethod.DAILY_ENTRY).dailyPriceVnd(5_000L)
                .build();
    }

    private static String applyJson() {
        return """
                {"parkingLotId":33,"vehicleType":"CAR","name":"Visitor tariff",
                 "calculationMethod":"DAILY_ENTRY","dailyPriceVnd":5000}
                """;
    }

    private static Jwt token(String authority) {
        return Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject("test-user").claim("authority", authority)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, FeePolicyController.class, GlobalExceptionHandler.class,
            FeePolicySecuritySettings.class})
    static class TestConfiguration {
        @Bean FeePolicyService feePolicyService() { return mock(FeePolicyService.class); }
        @Bean JwtAuthConverter jwtAuthConverter() { return mock(JwtAuthConverter.class); }
        @Bean UserContextFilter userContextFilter() { return new UserContextFilter(); }
    }

    @Configuration
    static class DecoderConfiguration {
        @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
    }
}
