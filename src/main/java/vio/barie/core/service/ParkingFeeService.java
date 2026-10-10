package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vio.barie.core.config.application.ParkingFeeSettings;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.*;
import vio.barie.core.dto.fee.calculation.*;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.entity.FeeCalculationEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.FeeCalculationRepository;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import vio.barie.core.utils.VehicleType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** JPA orchestration; calculators receive immutable values and never load the database. */
@Service
@Transactional(readOnly = true)
public class ParkingFeeService {
    public static final String ENGINE_REVISION = "parking-fee-v1";
    private final ParkingSessionRepository sessions;
    private final FeeCalculationRepository calculations;
    private final VehicleSubscriptionRepository subscriptionRepository;
    private final VehicleSubscriptionService subscriptions;
    private final FeePolicyService policies;
    private final SubscriptionCoverageCalculator coverageCalculator;
    private final FeeCalculator feeCalculator;
    private final ParkingFeeSettings settings;
    private final ParkingFeeWorkflowGuard workflowGuard;
    private final EntityManager entityManager;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ParkingFeeService(ParkingSessionRepository sessions, FeeCalculationRepository calculations,
            VehicleSubscriptionRepository subscriptionRepository, VehicleSubscriptionService subscriptions,
            FeePolicyService policies, SubscriptionCoverageCalculator coverageCalculator,
            FeeCalculator feeCalculator, ParkingFeeSettings settings, ParkingFeeWorkflowGuard workflowGuard,
            EntityManager entityManager,
            @Qualifier("feePolicyClock") Clock clock, ObjectMapper mapper) {
        this.sessions = sessions; this.calculations = calculations;
        this.subscriptionRepository = subscriptionRepository; this.subscriptions = subscriptions;
        this.policies = policies; this.coverageCalculator = coverageCalculator;
        this.feeCalculator = feeCalculator; this.settings = settings; this.workflowGuard = workflowGuard;
        this.entityManager = entityManager;
        this.clock = clock;
        this.mapper = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    }

    /** Display calculation never inserts an invoice, quote, receipt or session transition. */
    public ParkingFeeResponse simulate(ParkingFeeQueryRequest request) {
        require(request != null, "Request is required");
        ParkingSessionEntity session = session(request.sessionId());
        ZoneId sessionZone = settings.sessionZone();
        Instant entered = instant(session.getEntryTime(), sessionZone);
        Instant maximum = session.getExitTime() == null ? now() : instant(session.getExitTime(), sessionZone);
        Instant until = request.until() == null ? maximum : precise(request.until(), "until");
        require(!until.isBefore(entered) && !until.isAfter(maximum),
                "Simulation cutoff must be between entry and the actual exit/server time");
        TimeRange range = new TimeRange(entered, until);
        List<SubscriptionResponse> paid = range.isEmpty() ? List.of()
                : subscriptions.findPaidOverlapping(vehicleId(session), lotId(session), entered, until);
        FeeSnapshot snapshot = compute(session, range, sessionZone, settings.lotZone(lotId(session)),
                paid, settings.rules(), settings.getOutsidePricing(), settings.getEntitlementTiming());
        JsonNode detail = mapper.valueToTree(snapshot);
        FeeResult result = snapshot.result();
        return new ParkingFeeResponse(null, session.getId(), "SIMULATED", now(), until, null,
                result.subtotalVnd(), result.roundingAdjustmentVnd(), result.totalVnd(),
                fingerprint(detail), detail);
    }

    /** Payable cutoffs are chosen by the backend, never by a client requesting a cheaper past time. */
    @Transactional
    public ParkingFeeResponse quote(ParkingFeeQuoteRequest request) {
        require(request != null, "Request is required");
        Instant deadline = precise(request.paymentDeadlineAt(), "paymentDeadlineAt");
        require(deadline.isAfter(now()), "Payment deadline must be explicitly supplied in the future");
        ParkingSessionEntity preliminary = session(request.sessionId());
        Long originalVehicleId = vehicleId(preliminary);
        Long originalLotId = lotId(preliminary);
        ZoneId sessionZone = settings.sessionZone();
        // Lock vehicle first, even when the session has no purchased period.
        lockVehicle(originalVehicleId);
        ParkingSessionEntity session = sessions.lockForFee(request.sessionId())
                .orElseThrow(() -> notFound("Parking session not found"));
        entityManager.refresh(session);
        require(Objects.equals(vehicleId(session), originalVehicleId)
                        && Objects.equals(lotId(session), originalLotId),
                "Session scope changed while acquiring locks");
        entityManager.refresh(session.getVehicle());
        workflowGuard.assertNewWorkflowAllowed(session.getId());
        Instant entered = instant(session.getEntryTime(), sessionZone);
        Instant until = session.getExitTime() == null ? now() : instant(session.getExitTime(), sessionZone);
        require(!until.isBefore(entered), "Session entry is after its calculation cutoff");
        TimeRange range = new TimeRange(entered, until);
        if (range.isEmpty() && !Boolean.TRUE.equals(settings.getAllowZeroDurationSettlement()))
            throw decision("Zero-duration settlement has not been approved");
        List<SubscriptionResponse> paid = range.isEmpty() ? List.of()
                : subscriptions.lockPaidPeriodsForSettlement(vehicleId(session), lotId(session), entered, until);
        if (calculations.existsBySessionIdAndStatus(session.getId(), "SETTLED"))
            throw new BusinessException("This session already has a settled calculation; additional collection needs an approved workflow",
                    "FEE_SESSION_ALREADY_SETTLED", 409);
        FeeSnapshot snapshot = compute(session, range, sessionZone, settings.lotZone(lotId(session)),
                paid, settings.rules(), settings.getOutsidePricing(), settings.getEntitlementTiming());
        require(deadline.isAfter(now()), "Payment deadline expired while preparing the quote");
        JsonNode detail = mapper.valueToTree(snapshot);
        for (FeeCalculationEntity older : calculations.findBySessionIdAndStatusOrderByIdAsc(session.getId(), "QUOTED"))
            older.setStatus("SUPERSEDED");
        FeeCalculationEntity record = new FeeCalculationEntity();
        record.setSessionId(session.getId());
        record.setFeePolicyId(snapshot.allocations().size() == 1
                ? snapshot.allocations().getFirst().policy().getId() : null);
        record.setChargeContext(snapshot.chargeContext());
        record.setCalculatedAt(now()); record.setCalculationUntil(until); record.setValidUntil(deadline);
        record.setEngineRevision(ENGINE_REVISION); record.setInputFingerprint(fingerprint(detail));
        record.setSubtotalVnd(snapshot.result().subtotalVnd());
        record.setRoundingAdjustmentVnd(snapshot.result().roundingAdjustmentVnd());
        record.setTotalVnd(snapshot.result().totalVnd());
        record.setFeeDetail(detail); record.setStatus("QUOTED"); record.setCreatedAt(now());
        calculations.saveAndFlush(record);
        return response(record);
    }

    public ParkingFeeResponse getCalculation(Long calculationId) {
        return response(calculation(calculationId));
    }

    public List<ParkingFeeResponse> history(Long sessionId) {
        session(sessionId);
        return calculations.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId).stream()
                .map(this::response).toList();
    }

    /**
     * Same vehicle lock as subscription confirmation, then session, then calculation.
     * Financial evidence callers may retain REVIEW_REQUIRED when this returns SUPERSEDED.
     * Frozen tariffs are used: closing an old visitor version must not invalidate its quote.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FeeCalculationEntity lockForSettlement(Long calculationId) {
        require(!TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "Settlement requires a write transaction");
        FeeCalculationEntity preliminary = calculation(calculationId);
        ParkingSessionEntity before = session(preliminary.getSessionId());
        Long originalVehicleId = vehicleId(before);
        Long originalLotId = lotId(before);
        lockVehicle(originalVehicleId);
        ParkingSessionEntity current = sessions.lockForFee(before.getId())
                .orElseThrow(() -> notFound("Parking session not found"));
        entityManager.refresh(current);
        entityManager.refresh(current.getVehicle());
        FeeCalculationEntity record = calculations.lockById(calculationId)
                .orElseThrow(() -> notFound("Fee calculation not found"));
        entityManager.refresh(record);
        if (!"QUOTED".equals(record.getStatus())) return record;
        if (!Objects.equals(originalVehicleId, vehicleId(current))
                || !Objects.equals(originalLotId, lotId(current))
                || workflowGuard.hasLegacyPaymentHistory(current.getId())) {
            record.setStatus("SUPERSEDED");
            return record;
        }
        try {
            FeeSnapshot snapshot = mapper.treeToValue(record.getFeeDetail(), FeeSnapshot.class);
            boolean unchanged = ENGINE_REVISION.equals(record.getEngineRevision())
                    && ENGINE_REVISION.equals(snapshot.engineRevision())
                    && Objects.equals(record.getInputFingerprint(), fingerprint(record.getFeeDetail()))
                    && Objects.equals(snapshot.sessionId(), current.getId())
                    && Objects.equals(snapshot.vehicleId(), vehicleId(current))
                    && Objects.equals(snapshot.parkingLotId(), lotId(current))
                    && Objects.equals(snapshot.vehicleType(), vehicleType(current))
                    && Objects.equals(snapshot.entryTime(), current.getEntryTime())
                    && Objects.equals(snapshot.interval().end(), record.getCalculationUntil());
            if (snapshot.exitTime() != null)
                unchanged &= Objects.equals(snapshot.exitTime(), current.getExitTime());
            else if (current.getExitTime() != null)
                unchanged &= instant(current.getExitTime(), ZoneId.of(snapshot.sessionTimeZone()))
                        .equals(snapshot.interval().end());
            if (!unchanged) { record.setStatus("SUPERSEDED"); return record; }
            List<SubscriptionResponse> fresh = snapshot.interval().isEmpty() ? List.of()
                    : subscriptions.lockPaidPeriodsForSettlement(vehicleId(current), lotId(current),
                            snapshot.interval().start(), snapshot.interval().end());
            List<SubscriptionResponse> applied = entitlementPeriods(fresh, snapshot.interval(),
                    snapshot.entitlementTiming());
            CoverageResult refreshed = coverageCalculator.calculate(snapshot.interval(), applied,
                    ZoneId.of(snapshot.timezone()));
            if (!refreshed.equals(snapshot.coverage())) record.setStatus("SUPERSEDED");
        } catch (BusinessException exception) {
            // An entitlement requiring an unapproved convention invalidates the old quote;
            // caller still persists the authenticated money observation for reconciliation.
            record.setStatus("SUPERSEDED");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException exception) {
            record.setStatus("SUPERSEDED");
        }
        return record;
    }

    private FeeSnapshot compute(ParkingSessionEntity session, TimeRange range, ZoneId sessionZone,
            ZoneId lotZone, List<SubscriptionResponse> paid, FeeRules rules,
            ParkingFeeSettings.OutsidePricing outside, ParkingFeeSettings.EntitlementTiming timing) {
        List<SubscriptionResponse> applied = entitlementPeriods(paid, range, timing);
        CoverageResult coverage = coverageCalculator.calculate(range, applied, lotZone);
        boolean subscriber = applied.stream().anyMatch(period -> period.getStartsAt().isBefore(range.end())
                && period.getEndsAt().isAfter(range.start()));
        String context = subscriber ? "SUBSCRIPTION_OUTSIDE" : "VISITOR";
        List<PricingAllocation> allocations = new ArrayList<>();
        if (!coverage.uncovered().isEmpty()) {
            if (!subscriber || outside == ParkingFeeSettings.OutsidePricing.ENTRY_VERSION) {
                FeePolicyResponse pinned = policies.findForVisitor(lotId(session), vehicleType(session), range.start());
                coverage.uncovered().forEach(gap -> allocations.add(new PricingAllocation(gap, pinned)));
            } else if (outside == ParkingFeeSettings.OutsidePricing.EFFECTIVE_AT_TIME) {
                for (TimeRange gap : coverage.uncovered()) {
                    for (FeePolicyResponse policy : policies.findOverlapping(lotId(session), vehicleType(session),
                            gap.start(), gap.end())) {
                        Instant start = policy.getEffectiveFrom().isAfter(gap.start())
                                ? policy.getEffectiveFrom() : gap.start();
                        Instant end = policy.getEffectiveTo() == null || policy.getEffectiveTo().isAfter(gap.end())
                                ? gap.end() : policy.getEffectiveTo();
                        if (start.isBefore(end))
                            allocations.add(new PricingAllocation(new TimeRange(start, end), policy));
                    }
                }
            } else throw decision("Outside-subscription tariff selection has not been approved");
        }
        FeeResult result = feeCalculator.calculate(allocations, lotZone, rules);
        return new FeeSnapshot(ENGINE_REVISION, session.getId(), vehicleId(session), lotId(session),
                vehicleType(session), session.getEntryTime(), session.getExitTime(), range,
                lotZone.getId(), sessionZone.getId(), context, coverage, allocations, rules, outside, timing, result);
    }

    private List<SubscriptionResponse> entitlementPeriods(List<SubscriptionResponse> periods,
            TimeRange range, ParkingFeeSettings.EntitlementTiming timing) {
        List<SubscriptionResponse> result = new ArrayList<>();
        for (SubscriptionResponse source : periods) {
            if (source.getPaidAt() == null)
                throw new BusinessException("Paid period has no verified activation time", "FEE_ENTITLEMENT_INVALID", 409);
            SubscriptionResponse applied = source;
            if (source.getPaidAt().isAfter(range.start())) {
                if (timing == null) throw decision("Retroactive coverage after payment during/after the session has not been approved");
                if (timing == ParkingFeeSettings.EntitlementTiming.PAYMENT_TIME) {
                    applied = mapper.convertValue(source, SubscriptionResponse.class);
                    if (source.getPaidAt().isAfter(source.getStartsAt())) applied.setStartsAt(source.getPaidAt());
                    if (!applied.getStartsAt().isBefore(applied.getEndsAt())) continue;
                }
            }
            result.add(applied);
        }
        return List.copyOf(result);
    }

    private void lockVehicle(Long id) {
        VehicleEntity vehicle = subscriptionRepository.lockVehicleEntity(id)
                .orElseThrow(() -> notFound("Vehicle not found"));
        entityManager.refresh(vehicle);
    }

    private ParkingSessionEntity session(Long id) {
        positive(id, "sessionId");
        return sessions.findById(id).orElseThrow(() -> notFound("Parking session not found"));
    }
    private FeeCalculationEntity calculation(Long id) {
        positive(id, "calculationId");
        return calculations.findById(id).orElseThrow(() -> notFound("Fee calculation not found"));
    }
    private static Long vehicleId(ParkingSessionEntity session) { return session.getVehicle().getId(); }
    private static Long lotId(ParkingSessionEntity session) { return session.getParkingLot().getId(); }
    private static VehicleType vehicleType(ParkingSessionEntity session) {
        try { return VehicleType.valueOf(session.getVehicle().getVehicleType()); }
        catch (RuntimeException e) { throw new BusinessException("Unsupported vehicle type", "FEE_VEHICLE_TYPE_INVALID", 400); }
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Instant precise(Instant value, String field) {
        require(value != null, field + " is required");
        require(value.equals(value.truncatedTo(ChronoUnit.MICROS)), field + " exceeds PostgreSQL microsecond precision");
        return value;
    }
    /** Legacy timestamps have no offset; reject DST gaps/overlaps rather than guessing one. */
    private static Instant instant(LocalDateTime value, ZoneId zone) {
        require(value != null, "Session timestamp is required");
        var offsets = zone.getRules().getValidOffsets(value);
        if (offsets.size() != 1)
            throw new BusinessException("Legacy local timestamp has no unique instant in the configured zone",
                    "FEE_TIME_DECISION_REQUIRED", 409);
        return value.toInstant(offsets.getFirst()).truncatedTo(ChronoUnit.MICROS);
    }
    private String fingerprint(JsonNode tree) {
        try {
            byte[] bytes = mapper.writeValueAsString(canonical(tree)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private JsonNode canonical(JsonNode tree) {
        if (tree.isObject()) {
            ObjectNode node = mapper.createObjectNode();
            List<String> names = new ArrayList<>(); tree.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> node.set(name, canonical(tree.get(name))));
            return node;
        }
        if (tree.isArray()) {
            ArrayNode node = mapper.createArrayNode(); tree.forEach(value -> node.add(canonical(value))); return node;
        }
        return tree;
    }
    private ParkingFeeResponse response(FeeCalculationEntity record) {
        return new ParkingFeeResponse(record.getId(), record.getSessionId(), record.getStatus(),
                record.getCalculatedAt(), record.getCalculationUntil(), record.getValidUntil(),
                record.getSubtotalVnd(), record.getRoundingAdjustmentVnd(), record.getTotalVnd(),
                record.getInputFingerprint(), record.getFeeDetail().deepCopy());
    }
    private static void positive(Long id, String name) { require(id != null && id > 0, name + " must be positive"); }
    private static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException(message, "FEE_INVALID_REQUEST", 400);
    }
    private static BusinessException notFound(String message) { return new BusinessException(message, "NOT_FOUND", 404); }
    private static BusinessException decision(String message) { return new BusinessException(message, "FEE_RULE_DECISION_REQUIRED", 409); }
}
