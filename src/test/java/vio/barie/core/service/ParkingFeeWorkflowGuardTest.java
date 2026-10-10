package vio.barie.core.service;

import org.junit.jupiter.api.Test;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.repository.*;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentGateway;
import vio.barie.core.service.smoothing.SmoothingAIEvents;
import vio.barie.core.verification.ParkingSessionLifecycleService;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParkingFeeWorkflowGuardTest {
    @Test
    void conflictingFeeHistoryStopsLegacyExitBeforeClosureGatewayOrMqtt() {
        ParkingFeeWorkflowGuard guard = mock(ParkingFeeWorkflowGuard.class);
        BusinessException conflict = new BusinessException("Conflict", "FEE_WORKFLOW_CONFLICT", 409);
        when(guard.lockAndAssertLegacyWorkflowAllowed(40L)).thenThrow(conflict);
        TicketRepository tickets = mock(TicketRepository.class);
        ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
        ParkingMessageService iot = mock(ParkingMessageService.class);
        CalculatorService calculator = mock(CalculatorService.class);
        PaymentService payments = mock(PaymentService.class);
        SmoothingAIEvents smoothing = mock(SmoothingAIEvents.class);
        ParkingSessionLifecycleService lifecycle = new ParkingSessionLifecycleService(tickets, sessions,
                iot, calculator, payments, smoothing);
        lifecycle.setFeeWorkflowGuard(guard);
        ParkingSessionEntity session = new ParkingSessionEntity(); session.setId(40L);
        assertSame(conflict, assertThrows(BusinessException.class,
                () -> lifecycle.closeExit(session, null, null, null)));
        assertNull(session.getExitTime());
        verifyNoInteractions(tickets, sessions, iot, calculator, payments, smoothing);
    }

    @Test
    void directLegacyPaymentPathStopsBeforeSupersedingOrSavingPayments() {
        ParkingFeeWorkflowGuard guard = mock(ParkingFeeWorkflowGuard.class);
        when(guard.lockAndAssertLegacyWorkflowAllowed(40L)).thenThrow(
                new BusinessException("Conflict", "FEE_WORKFLOW_CONFLICT", 409));
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentGateway gateway = mock(PaymentGateway.class);
        PaymentService payments = new PaymentService(repository, gateway,
                mock(StringRedisTemplate.class), mock(SubscriptionPaymentReportService.class));
        payments.setFeeWorkflowGuard(guard);
        BusinessException failure = assertThrows(BusinessException.class, () -> payments.createSessionPayment(
                "invoice", BigDecimal.valueOf(1000), "QR", "Fee", 40L, "barrier", "plate", null,
                2L, LocalDateTime.now()));
        assertEquals("FEE_WORKFLOW_CONFLICT", failure.getCode());
        verifyNoInteractions(repository, gateway);
    }
}
