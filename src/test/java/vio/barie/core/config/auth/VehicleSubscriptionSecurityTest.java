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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.common.SubscriptionReceiptOutcome;
import vio.barie.core.config.exception.GlobalExceptionHandler;
import vio.barie.core.config.filter.UserContextFilter;
import vio.barie.core.controller.SubscriptionPackageController;
import vio.barie.core.controller.VehicleSubscriptionController;
import vio.barie.core.controller.VehicleSubscriptionPaymentController;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.subscription.SubscriptionPackageResponse;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.dto.subscription.SubscriptionReceiptResponse;
import vio.barie.core.service.VehicleSubscriptionService;
import vio.barie.core.service.VehicleSubscriptionPaymentService;
import vio.barie.core.service.SubscriptionPackageService;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP/security chain, with a mocked domain service and no database or external JWT issuer. */
class VehicleSubscriptionSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private VehicleSubscriptionService service;

    @BeforeEach
    void setUp() {
        configure(true, true, null);
    }

    private void configure(Boolean authEnabled, boolean includeDecoder, String environmentOverride) {
        if (context != null) {
            context.close();
        }
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // The fee module's own security test verifies its behavior independently.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "fee-module-disabled", Map.of("app.fee-policy.auth-enabled", "false")));
        if (authEnabled != null) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "subscription-test", Map.of("app.vehicle-subscription.auth-enabled", authEnabled.toString())));
        }
        if (environmentOverride != null) {
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "subscription-test-environment", Map.of("APP_VEHICLE_SUBSCRIPTION_AUTH_ENABLED", environmentOverride)));
        }
        context.register(TestConfiguration.class);
        if (includeDecoder) {
            context.register(DecoderConfiguration.class);
        }
        context.refresh();
        service = context.getBean(VehicleSubscriptionService.class);
        if (includeDecoder) {
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            when(decoder.decode("viewer")).thenReturn(token("ROLE_ORG_VIEW"));
            when(decoder.decode("editor")).thenReturn(token("ROLE_ORG_EDIT"));
            when(decoder.decode("wrong-role")).thenReturn(token("ROLE_UNKNOWN"));
            when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid test token"));
        }
        when(context.getBean(JwtAuthConverter.class).convert(any(Jwt.class))).thenAnswer(invocation -> {
            Jwt jwt = invocation.getArgument(0);
            return new UsernamePasswordAuthenticationToken(jwt.getSubject(), null,
                    List.of(new SimpleGrantedAuthority(jwt.getClaimAsString("authority"))));
        });
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void enabledAuthRejectsAnonymousRegistration() throws Exception {
        mvc.perform(post("/api/vehicle-subscriptions/register")
                        .contentType("application/json").content(registerJson()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void enabledAuthRejectsAnonymousCatalog() throws Exception {
        mvc.perform(get("/api/subscription-packages")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void packagePublicationRequiresEditorWhenAuthenticationIsEnabled() throws Exception {
        mvc.perform(post("/api/subscription-packages/2/publish").header("Authorization", "Bearer viewer"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(SubscriptionPackageService.class));
        mvc.perform(post("/api/subscription-packages/2/publish").header("Authorization", "Bearer editor"))
                .andExpect(status().isOk());
        verify(context.getBean(SubscriptionPackageService.class)).publish(2L);
    }

    @Test
    void packageDraftWritesUseExistingDevelopmentAuthenticationSwitch() throws Exception {
        configure(false, false, null);
        mvc.perform(post("/api/subscription-packages").contentType("application/json").content("""
                {"packageCode":"MONTHLY","name":"Monthly","parkingLotId":33,"vehicleType":"CAR",
                 "priceVnd":300000,"durationDays":30,"coverageMode":"ALL_DAY",
                 "effectiveFrom":"2026-10-09T00:00:00Z","saleEnabled":true,"schedules":[]}
                """))
                .andExpect(status().isOk());
        verify(context.getBean(SubscriptionPackageService.class)).createDraft(any());
        mvc.perform(put("/api/subscription-packages/2/sale").contentType("application/json")
                        .content("{\"saleEnabled\":false,\"effectiveTo\":null}"))
                .andExpect(status().isOk());
        verify(context.getBean(SubscriptionPackageService.class)).updateSale(eq(2L), any());
    }

    @Test
    void viewerCanReadSubscriptionAndPackage() throws Exception {
        when(service.getById(101L)).thenReturn(response());
        when(service.availablePackages(33L, VehicleType.CAR))
                .thenReturn(List.of(SubscriptionPackageResponse.builder().id(2L).name("Month pass").build()));
        mvc.perform(get("/api/vehicle-subscriptions/101").header("Authorization", "Bearer viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(101));
        mvc.perform(get("/api/subscription-packages").header("Authorization", "Bearer viewer")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(2));
    }

    @Test
    void viewerCanReadActualReceiptAmountAndReviewReasonWithoutProviderMetadata() throws Exception {
        when(service.paymentReceipts(101L)).thenReturn(List.of(new SubscriptionReceiptResponse(
                201L, 101L, 301L, "SEPAY", "test-transfer", "test-invoice", "VND", 4_000L,
                null, Instant.parse("2026-10-09T00:00:00Z"), SubscriptionReceiptOutcome.REVIEW_REQUIRED,
                "AMOUNT_MISMATCH", Instant.parse("2026-10-09T00:00:00Z"))));
        mvc.perform(get("/api/vehicle-subscriptions/101/receipts").header("Authorization", "Bearer viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].amountVnd").value(4_000))
                .andExpect(jsonPath("$.data[0].outcome").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data[0].reasonCode").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.data[0].metadata").doesNotExist());
    }

    @Test
    void anonymousCannotReadReceiptAuditWhenAuthIsEnabled() throws Exception {
        mvc.perform(get("/api/vehicle-subscriptions/101/receipts"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void wrongRoleCannotReadReceiptAudit() throws Exception {
        mvc.perform(get("/api/vehicle-subscriptions/101/receipts").header("Authorization", "Bearer wrong-role"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void wrongRoleCannotReadSubscription() throws Exception {
        mvc.perform(get("/api/vehicle-subscriptions/101").header("Authorization", "Bearer wrong-role"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void viewerCannotRegisterOrRenew() throws Exception {
        mvc.perform(post("/api/vehicle-subscriptions/register").header("Authorization", "Bearer viewer")
                        .contentType("application/json").content(registerJson()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/vehicle-subscriptions/renew").header("Authorization", "Bearer viewer")
                        .contentType("application/json").content(renewJson()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void editorCanRegisterAndRenew() throws Exception {
        when(service.register(any())).thenReturn(response());
        when(service.renew(any())).thenReturn(response());
        mvc.perform(post("/api/vehicle-subscriptions/register").header("Authorization", "Bearer editor")
                        .contentType("application/json").content(registerJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));
        mvc.perform(post("/api/vehicle-subscriptions/renew").header("Authorization", "Bearer editor")
                        .contentType("application/json").content(renewJson()))
                .andExpect(status().isOk());
        verify(service).register(any());
        verify(service).renew(any());
    }

    @Test
    void invalidJwtIsRejected() throws Exception {
        mvc.perform(get("/api/vehicle-subscriptions/101").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verify(context.getBean(JwtDecoder.class)).decode("invalid");
        verifyNoInteractions(service);
    }

    @Test
    void absentFlagRequiresAuthentication() throws Exception {
        configure(null, true, null);
        assertTrue(context.getBean(VehicleSubscriptionSecuritySettings.class).isAuthEnabled());
        mvc.perform(get("/api/vehicle-subscriptions/101")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void disabledAuthAllowsAnonymousRegisterAndRenewWithoutDecoder() throws Exception {
        configure(false, false, null);
        assertFalse(context.containsBean("jwtDecoder"));
        when(service.register(any())).thenReturn(response());
        when(service.renew(any())).thenReturn(response());
        mvc.perform(post("/api/vehicle-subscriptions/register")
                        .contentType("application/json").content(registerJson()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/vehicle-subscriptions/renew")
                        .contentType("application/json").content(renewJson()))
                .andExpect(status().isOk());
        verify(service).register(any());
        verify(service).renew(any());
        verifyNoInteractions(context.getBean(JwtAuthConverter.class));
    }

    @Test
    void disabledAuthAllowsEveryReadRouteWithoutDecoder() throws Exception {
        configure(false, false, null);
        Instant from = Instant.parse("2026-10-09T00:00:00Z");
        Instant until = Instant.parse("2026-10-10T00:00:00Z");
        when(service.current(9L, 33L)).thenReturn(List.of(response()));
        when(service.history(9L, 33L)).thenReturn(List.of(response()));
        when(service.findPaidOverlapping(9L, 33L, from, until)).thenReturn(List.of(response()));
        when(service.getById(101L)).thenReturn(response());
        when(service.availablePackages(33L, VehicleType.CAR))
                .thenReturn(List.of(SubscriptionPackageResponse.builder().id(2L).build()));
        mvc.perform(get("/api/vehicle-subscriptions/current")
                        .param("vehicleId", "9").param("parkingLotId", "33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(101));
        mvc.perform(get("/api/vehicle-subscriptions/history")
                        .param("vehicleId", "9").param("parkingLotId", "33"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vehicle-subscriptions/paid")
                        .param("vehicleId", "9").param("parkingLotId", "33")
                        .param("from", from.toString()).param("until", until.toString()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vehicle-subscriptions/101")).andExpect(status().isOk());
        mvc.perform(get("/api/vehicle-subscriptions/101/receipts")).andExpect(status().isOk());
        mvc.perform(get("/api/subscription-packages")
                        .param("parkingLotId", "33").param("vehicleType", "CAR"))
                .andExpect(status().isOk());
        verify(service).current(9L, 33L);
        verify(service).history(9L, 33L);
        verify(service).findPaidOverlapping(9L, 33L, from, until);
        verify(service).getById(101L);
        verify(service).paymentReceipts(101L);
        verify(service).availablePackages(33L, VehicleType.CAR);
        verifyNoInteractions(context.getBean(JwtAuthConverter.class));
    }

    @Test
    void disabledAuthDoesNotTryToDecodeStaleBearerToken() throws Exception {
        configure(false, true, null);
        when(service.getById(101L)).thenReturn(response());
        mvc.perform(get("/api/vehicle-subscriptions/101").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk());
        verifyNoInteractions(context.getBean(JwtDecoder.class), context.getBean(JwtAuthConverter.class));
    }

    @Test
    void anonymousBodyMustStillSupplyExplicitDatesAndPositiveIdentifiers() throws Exception {
        configure(false, false, null);
        mvc.perform(post("/api/vehicle-subscriptions/register").contentType("application/json")
                        .content("""
                                {"vehicleId":0,"parkingLotId":33,"packageId":2,"requestKey":""}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/vehicle-subscriptions/renew").contentType("application/json")
                        .content("""
                                {"previousSubscriptionId":101,"packageId":2,"requestKey":"renew-1"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void anonymousReadStillValidatesQueryAndPathParameters() throws Exception {
        configure(false, false, null);
        mvc.perform(get("/api/vehicle-subscriptions/history")
                        .param("vehicleId", "-1").param("parkingLotId", "33"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/vehicle-subscriptions/0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/subscription-packages")
                        .param("parkingLotId", "0").param("vehicleType", "CAR"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void noBrowserPaymentConfirmationRouteIsExposed() throws Exception {
        configure(false, false, null);
        mvc.perform(post("/api/vehicle-subscriptions/101/confirm-payment")
                        .contentType("application/json").content("{\"paid\":true,\"priceVnd\":1}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void disabledAuthAllowsPaymentLinkByIdentifierWithoutAcceptingClientAmount() throws Exception {
        configure(false, false, null);
        VehicleSubscriptionPaymentService payments = context.getBean(VehicleSubscriptionPaymentService.class);
        CreatePaymentResponse response = new CreatePaymentResponse();
        response.setAmount(BigDecimal.valueOf(200_000));
        response.setInvoiceNo("SUB-101");
        when(payments.createPaymentLink(101L)).thenReturn(response);
        mvc.perform(post("/api/vehicle-subscriptions/101/payment-link")
                        .contentType("application/json").content("{\"amount\":1,\"paid\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(200000));
        verify(payments).createPaymentLink(101L);
        verifyNoInteractions(service, context.getBean(JwtAuthConverter.class));
    }

    @Test
    void viewerCannotCreatePaymentLink() throws Exception {
        mvc.perform(post("/api/vehicle-subscriptions/101/payment-link")
                        .header("Authorization", "Bearer viewer"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(VehicleSubscriptionPaymentService.class));
    }

    @Test
    void paymentLinkIdentifierMustBePositiveWhenAuthIsDisabled() throws Exception {
        configure(false, false, null);
        mvc.perform(post("/api/vehicle-subscriptions/0/payment-link")).andExpect(status().isBadRequest());
        verifyNoInteractions(context.getBean(VehicleSubscriptionPaymentService.class));
    }

    @Test
    void environmentVariableCanDisableModuleAuthentication() throws Exception {
        configure(true, false, "false");
        assertFalse(context.getBean(VehicleSubscriptionSecuritySettings.class).isAuthEnabled());
        when(service.getById(101L)).thenReturn(response());
        mvc.perform(get("/api/vehicle-subscriptions/101")).andExpect(status().isOk());
        verifyNoInteractions(context.getBean(JwtAuthConverter.class));
    }

    @Test
    void unrelatedLegacyRouteRetainsItsExistingFallback() throws Exception {
        configure(false, false, null);
        mvc.perform(get("/legacy-not-mapped")).andExpect(status().isNotFound());
    }

    private static SubscriptionResponse response() {
        return SubscriptionResponse.builder().id(101L).vehicleId(9L).parkingLotId(33L).packageId(2L)
                .priceVnd(200_000L).status(VehicleSubscriptionStatus.PENDING_PAYMENT).build();
    }

    private static String registerJson() {
        return """
                {"vehicleId":9,"parkingLotId":33,"packageId":2,"requestKey":"register-1",
                 "startsAt":"2026-10-10T00:00:00+07:00","paymentDeadlineAt":"2026-10-09T22:00:00+07:00"}
                """;
    }

    private static String renewJson() {
        return """
                {"previousSubscriptionId":101,"packageId":2,"requestKey":"renew-1",
                 "paymentDeadlineAt":"2026-10-09T22:00:00+07:00"}
                """;
    }

    private static Jwt token(String authority) {
        return Jwt.withTokenValue("test-token").header("alg", "RS256").subject("test-user")
                .claim("authority", authority).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, FeePolicySecuritySettings.class, GlobalExceptionHandler.class,
            VehicleSubscriptionController.class, SubscriptionPackageController.class,
            VehicleSubscriptionPaymentController.class})
    static class TestConfiguration {
        @Bean VehicleSubscriptionService vehicleSubscriptionService() { return mock(VehicleSubscriptionService.class); }
        @Bean vio.barie.core.service.VehicleSubscriptionListingService vehicleSubscriptionListingService() {
            return mock(vio.barie.core.service.VehicleSubscriptionListingService.class);
        }
        @Bean SubscriptionPackageService subscriptionPackageService() { return mock(SubscriptionPackageService.class); }
        @Bean VehicleSubscriptionPaymentService vehicleSubscriptionPaymentService() { return mock(VehicleSubscriptionPaymentService.class); }
        @Bean JwtAuthConverter jwtAuthConverter() { return mock(JwtAuthConverter.class); }
        @Bean UserContextFilter userContextFilter() { return new UserContextFilter(); }
    }

    @Configuration
    static class DecoderConfiguration {
        @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
    }
}

