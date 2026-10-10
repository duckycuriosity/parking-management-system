package vio.barie.core.entity;

import org.junit.jupiter.api.Test;
import vio.barie.core.common.SubscriptionReceiptOutcome;
import vio.barie.core.common.SubscriptionRegistrationType;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.exception.BusinessException;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Java-side guards remain effective when the historical SQL business triggers are removed. */
class VehicleSubscriptionLifecycleTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant DEADLINE = START.plusSeconds(86_400);

    @Test
    void persistedPendingQuoteCannotBeRepricedOrMoved() {
        VehicleSubscriptionEntity order = pending();
        order.rememberPersistedFacts();
        order.setPriceVnd(700_000L);
        assertThrows(BusinessException.class, order::validateOrderUpdate);
        order.setPriceVnd(500_000L);
        order.setEndsAt(order.getEndsAt().plusSeconds(86_400));
        assertThrows(BusinessException.class, order::validateOrderUpdate);
    }

    @Test
    void verifiedGrantCanSetPaymentFactsOnceAndPaidPeriodThenFreezes() {
        VehicleSubscriptionEntity order = pending();
        order.rememberPersistedFacts();
        order.setStatus(VehicleSubscriptionStatus.PAID);
        order.setPaidAt(DEADLINE);
        order.setPaidReceiptId(9L);
        assertDoesNotThrow(order::validateOrderUpdate);
        order.rememberPersistedFacts();
        // An automatic updated_at change is not a change to the period or financial facts.
        order.setUpdatedAt(DEADLINE.plusSeconds(10));
        assertDoesNotThrow(order::validateOrderUpdate);
        order.setPaidReceiptId(10L);
        assertThrows(BusinessException.class, order::validateOrderUpdate);
        assertThrows(BusinessException.class, order::preventPaidOrderDeletion);
    }

    @Test
    void receiptCannotBeUpdatedOrDeletedEvenOutsideTheDomainService() {
        VehicleSubscriptionReceiptEntity receipt = applied();
        assertDoesNotThrow(receipt::validateNewReceipt);
        receipt.setAmountVnd(700_000L);
        assertThrows(BusinessException.class, receipt::preventReceiptUpdate);
        assertThrows(BusinessException.class, receipt::preventReceiptDeletion);
    }

    @Test
    void missingPhysicalPaymentTimeIsRetainedOnlyAsReviewEvidence() {
        VehicleSubscriptionReceiptEntity receipt = applied();
        receipt.setPaidAt(null);
        assertThrows(BusinessException.class, receipt::validateNewReceipt);
        receipt.setOutcome(SubscriptionReceiptOutcome.REVIEW_REQUIRED);
        receipt.setReasonCode("PAYMENT_TIME_UNKNOWN");
        assertDoesNotThrow(receipt::validateNewReceipt);
    }

    @Test
    void invalidOrderShapesAreRejectedBeforeInsert() {
        VehicleSubscriptionEntity order = pending();
        order.setEndsAt(order.getStartsAt());
        assertThrows(BusinessException.class, order::validateNewOrder);
        order = pending();
        order.setRegistrationType(SubscriptionRegistrationType.RENEW);
        assertThrows(BusinessException.class, order::validateNewOrder);
        order = pending();
        order.setStatus(VehicleSubscriptionStatus.PAID);
        order.setPaidAt(DEADLINE);
        order.setPaidReceiptId(9L);
        assertThrows(BusinessException.class, order::validateNewOrder);
    }

    @Test
    void reviewedOrderCannotBeReopenedByARepositoryMutation() {
        VehicleSubscriptionEntity order = pending();
        order.setStatus(VehicleSubscriptionStatus.PAYMENT_EXCEPTION);
        order.rememberPersistedFacts();
        order.setStatus(VehicleSubscriptionStatus.PENDING_PAYMENT);
        assertThrows(BusinessException.class, order::validateOrderUpdate);
    }

    private static VehicleSubscriptionEntity pending() {
        SubscriptionPackageEntity pack = new SubscriptionPackageEntity();
        pack.setId(3L);
        VehicleSubscriptionEntity order = new VehicleSubscriptionEntity();
        order.setId(1L);
        order.setRequestKey("request-1");
        order.setVehicleId(1L);
        order.setParkingLotId(1L);
        order.setSubscriptionPackage(pack);
        order.setRegistrationType(SubscriptionRegistrationType.NEW);
        order.setPriceVnd(500_000L);
        order.setStartsAt(START);
        order.setEndsAt(START.plusSeconds(30L * 86_400));
        order.setPaymentDeadlineAt(DEADLINE);
        order.setCreatedAt(START);
        return order;
    }

    private static VehicleSubscriptionReceiptEntity applied() {
        VehicleSubscriptionReceiptEntity receipt = new VehicleSubscriptionReceiptEntity();
        receipt.setSubscriptionId(1L);
        receipt.setPaymentId(2L);
        receipt.setSource("SEPAY");
        receipt.setReference("TX001");
        receipt.setInvoiceNo("INV001");
        receipt.setCurrency("VND");
        receipt.setAmountVnd(500_000L);
        receipt.setPaidAt(DEADLINE);
        receipt.setReceivedAt(DEADLINE.plusSeconds(5));
        receipt.setOutcome(SubscriptionReceiptOutcome.APPLIED);
        receipt.setMetadata(Map.of("verified", true));
        return receipt;
    }
}
