package vio.barie.core.dto.fee.calculation;

import vio.barie.core.dto.fee.FeePolicyResponse;

import java.util.Objects;

/** The caller selects the tariff. Calculators never select a version or read the DB. */
public record PricingAllocation(TimeRange range, FeePolicyResponse policy) {
    public PricingAllocation {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(policy, "policy");
    }
}
