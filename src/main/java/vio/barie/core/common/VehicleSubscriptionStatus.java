package vio.barie.core.common;

/** Settlement state; current entitlement also depends on the period and package schedule. */
public enum VehicleSubscriptionStatus {
    PENDING_PAYMENT,
    PAID,
    CANCELLED,
    PAYMENT_EXCEPTION
}
