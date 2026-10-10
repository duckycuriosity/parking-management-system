package vio.barie.core.service;

import jakarta.persistence.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.service.payment.common.PaymentGateway;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VehicleSubscriptionPaymentReportTest {
    private PaymentRepository payments;
    private SubscriptionPaymentReportService receipts;
    private PaymentService service;

    @BeforeEach
    void createReportService() {
        payments = mock(PaymentRepository.class);
        receipts = mock(SubscriptionPaymentReportService.class);
        service = new PaymentService(payments, mock(PaymentGateway.class),
                mock(StringRedisTemplate.class), receipts);
    }

    @Test
    void twoRealTransfersCountSixHundredThousandWithoutCountingPaymentQuoteAgain() {
        Tuple legacy = mock(Tuple.class);
        when(legacy.get("revenue", BigDecimal.class)).thenReturn(BigDecimal.ZERO);
        when(legacy.get("successful_count")).thenReturn(1L); // one subscription payment request
        when(legacy.get("pending_count")).thenReturn(0L);
        when(legacy.get("failed_count")).thenReturn(0L);
        when(payments.getRevenueSummary(any(), any(), eq(1L), eq(9L))).thenReturn(legacy);
        when(receipts.summarize(any(), any(), eq(1L), eq(9L))).thenReturn(twoTransfers());

        var result = service.getDailyRevenueSummary(1L, 9L, "2026-10-09", "2026-10-09");

        assertEquals(new BigDecimal("600000"), result.getTotalReceivedAmount());
        assertEquals(new BigDecimal("300000"), result.getTotalRevenue());
        assertEquals(new BigDecimal("300000"), result.getSubscriptionAppliedAmount());
        assertEquals(new BigDecimal("300000"), result.getSubscriptionReviewRequiredAmount());
        assertEquals(2L, result.getSubscriptionReceivedTransactions());
        assertEquals(1L, result.getSuccessfulTransactions());
        verify(receipts).summarize(LocalDateTime.parse("2026-10-09T00:00"),
                LocalDateTime.parse("2026-10-10T00:00"), 1L, 9L);
    }

    @Test
    void dailyReportMergesAppliedCashAndKeepsReviewCashSeparateFromRevenue() {
        when(payments.findDailyRevenue(any(), any(), anyList(), eq(9L), eq(1L)))
                .thenReturn(List.<Object[]>of(new Object[]{"09/10/2026", 1L, new BigDecimal("20000")}));
        when(receipts.daily(any(), any(), eq(1L), eq(9L))).thenReturn(List.of(
                new SubscriptionPaymentReportService.DailyCash(LocalDate.parse("2026-10-09"), twoTransfers())));

        var result = service.getRevenueReport("2026-10-09", "2026-10-09", 9L, 1L, 0, 10);

        assertEquals(new BigDecimal("320000"), result.getTotalRevenue());
        assertEquals(new BigDecimal("620000"), result.getTotalReceivedAmount());
        assertEquals(new BigDecimal("300000"), result.getSubscriptionReviewRequiredAmount());
        assertEquals(2L, result.getTotalTransactions()); // one legacy + one APPLIED
        assertEquals(3L, result.getTotalReceivedTransactions());
        var day = result.getDailyDetails().getContent().getFirst();
        assertEquals(new BigDecimal("620000"), day.getReceivedAmount());
        assertEquals(3L, day.getReceivedTransactionCount());
        assertEquals(1L, result.getDailyDetails().getTotalElements());
    }

    @Test
    void reviewOnlyDateIsStillVisibleEvenWithoutAppliedOrLegacyRevenue() {
        when(payments.findDailyRevenue(any(), any(), anyList(), eq(9L), eq(1L))).thenReturn(List.of());
        when(receipts.daily(any(), any(), eq(1L), eq(9L))).thenReturn(List.of(
                new SubscriptionPaymentReportService.DailyCash(LocalDate.parse("2026-10-09"),
                        new SubscriptionPaymentReportService.CashTotals(new BigDecimal("123000"),
                                BigDecimal.ZERO, new BigDecimal("123000"), 1L, 0L))));

        var result = service.getRevenueReport("2026-10-09", "2026-10-09", 9L, 1L, 0, 10);

        assertEquals(BigDecimal.ZERO, result.getTotalRevenue());
        assertEquals(new BigDecimal("123000"), result.getTotalReceivedAmount());
        assertEquals(0L, result.getTotalTransactions());
        assertEquals(1L, result.getTotalReceivedTransactions());
        assertEquals("09/10/2026", result.getDailyDetails().getContent().getFirst().getDate());
    }

    private SubscriptionPaymentReportService.CashTotals twoTransfers() {
        return new SubscriptionPaymentReportService.CashTotals(new BigDecimal("600000"),
                new BigDecimal("300000"), new BigDecimal("300000"), 2L, 1L);
    }
}
