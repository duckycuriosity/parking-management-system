package vio.barie.core.common;

/** Computed time state; only PAID subscriptions can have a usable period. */
public enum SubscriptionPeriodStatus {
    NONE,
    UPCOMING,
    IN_TERM,
    EXPIRED
}
