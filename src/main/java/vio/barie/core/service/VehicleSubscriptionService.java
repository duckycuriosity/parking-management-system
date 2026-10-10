package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vio.barie.core.common.*;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.subscription.*;
import vio.barie.core.entity.*;
import vio.barie.core.event.SubscriptionEntitlementsChanged;
import vio.barie.core.repository.*;
import vio.barie.core.utils.VehicleType;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/** Pending purchases do not grant rights. Each renewal creates an immutable paid period. */
@Service
@Transactional(readOnly = true)
public class VehicleSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(VehicleSubscriptionService.class);
    private final VehicleSubscriptionRepository subscriptions;
    private final SubscriptionPackageRepository packages;
    private final VehicleSubscriptionReceiptRepository receipts;
    private final PaymentRepository payments;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    public VehicleSubscriptionService(VehicleSubscriptionRepository subscriptions,
            SubscriptionPackageRepository packages, VehicleSubscriptionReceiptRepository receipts,
            PaymentRepository payments, @Qualifier("feePolicyClock") Clock clock, EntityManager entityManager,
            ApplicationEventPublisher events) {
        this.subscriptions = subscriptions;
        this.packages = packages;
        this.receipts = receipts;
        this.payments = payments;
        this.clock = clock;
        this.entityManager = entityManager;
        this.events = events;
    }

    @Transactional
    public SubscriptionResponse register(RegisterSubscriptionRequest request) {
        require(request != null, "Request is required");
        positive(request.getVehicleId(), "vehicleId");
        positive(request.getParkingLotId(), "parkingLotId");
        positive(request.getPackageId(), "packageId");
        requestKey(request.getRequestKey());
        preciseTime(request.getStartsAt(), "startsAt");
        preciseTime(request.getPaymentDeadlineAt(), "paymentDeadlineAt");
        lockVehicle(request.getVehicleId());
        VehicleSubscriptionEntity existing = subscriptions.findByRequestKey(request.getRequestKey()).orElse(null);
        if (existing != null) {
            boolean identical = existing.getRegistrationType() == SubscriptionRegistrationType.NEW
                    && Objects.equals(existing.getVehicleId(), request.getVehicleId())
                    && Objects.equals(existing.getParkingLotId(), request.getParkingLotId())
                    && Objects.equals(existing.getPackageId(), request.getPackageId())
                    && Objects.equals(existing.getStartsAt(), request.getStartsAt())
                    && Objects.equals(existing.getPaymentDeadlineAt(), request.getPaymentDeadlineAt());
            return repeat(existing, identical);
        }
        Instant now = now();
        validDeadline(request.getPaymentDeadlineAt(), now);
        SubscriptionPackageEntity pack = salePackage(request.getPackageId(), request.getVehicleId(),
                request.getParkingLotId(), now);
        validDeadline(request.getPaymentDeadlineAt(), now());
        VehicleSubscriptionEntity order = newOrder(request.getRequestKey(), request.getVehicleId(),
                request.getParkingLotId(), pack, request.getStartsAt(), request.getPaymentDeadlineAt());
        order.setRegistrationType(SubscriptionRegistrationType.NEW);
        ensureNoPaidOverlap(order);
        return response(saveOrder(order), now);
    }

    @Transactional
    public SubscriptionResponse renew(RenewSubscriptionRequest request) {
        require(request != null, "Request is required");
        positive(request.getPreviousSubscriptionId(), "previousSubscriptionId");
        positive(request.getPackageId(), "packageId");
        requestKey(request.getRequestKey());
        preciseTime(request.getPaymentDeadlineAt(), "paymentDeadlineAt");
        VehicleSubscriptionEntity previous = lockedSubscription(request.getPreviousSubscriptionId());
        VehicleSubscriptionEntity existing = subscriptions.findByRequestKey(request.getRequestKey()).orElse(null);
        if (existing != null) {
            boolean identical = existing.getRegistrationType() == SubscriptionRegistrationType.RENEW
                    && Objects.equals(existing.getPreviousSubscriptionId(), previous.getId())
                    && Objects.equals(existing.getPackageId(), request.getPackageId())
                    && Objects.equals(existing.getPaymentDeadlineAt(), request.getPaymentDeadlineAt());
            return repeat(existing, identical);
        }
        require(previous.getStatus() == VehicleSubscriptionStatus.PAID, "Only a paid period can be renewed");
        Instant now = now();
        validDeadline(request.getPaymentDeadlineAt(), now);
        SubscriptionPackageEntity pack = salePackage(request.getPackageId(), previous.getVehicleId(),
                previous.getParkingLotId(), now);
        validDeadline(request.getPaymentDeadlineAt(), now());
        VehicleSubscriptionEntity order = newOrder(request.getRequestKey(), previous.getVehicleId(),
                previous.getParkingLotId(), pack, previous.getEndsAt(), request.getPaymentDeadlineAt());
        order.setRegistrationType(SubscriptionRegistrationType.RENEW);
        order.setPreviousSubscriptionId(previous.getId());
        ensureNoPaidOverlap(order);
        return response(saveOrder(order), now);
    }

    public SubscriptionResponse getById(Long id) {
        positive(id, "subscriptionId");
        return response(subscription(id), now());
    }

    /** Bounded aggregate/replay response; fetch all required package schedules in one query. */
    public List<SubscriptionResponse> getByIds(List<Long> ids) {
        require(ids != null && ids.size() <= 100, "Require at most 100 subscription identifiers");
        if (ids.isEmpty()) return List.of();
        ids.forEach(id -> positive(id, "subscriptionId"));
        List<Long> uniqueIds = ids.stream().distinct().toList();
        java.util.Map<Long, VehicleSubscriptionEntity> found = subscriptions.findWithSchedulesByIds(uniqueIds).stream()
                .collect(java.util.stream.Collectors.toMap(VehicleSubscriptionEntity::getId, java.util.function.Function.identity()));
        if (found.size() != uniqueIds.size()) throw error("NOT_FOUND", "Subscription not found", 404);
        Instant at = now();
        return uniqueIds.stream().map(id -> response(found.get(id), at)).toList();
    }

    public List<SubscriptionReceiptResponse> paymentReceipts(Long id) {
        positive(id, "subscriptionId");
        subscription(id); // A missing order is a 404, rather than an empty payment history.
        return receipts.findBySubscriptionIdOrderByReceivedAtAscIdAsc(id).stream()
                .map(r -> new SubscriptionReceiptResponse(r.getId(), r.getSubscriptionId(), r.getPaymentId(),
                        r.getSource(), r.getReference(), r.getInvoiceNo(), r.getCurrency(), r.getAmountVnd(),
                        r.getPaidAt(), r.getReceivedAt(), r.getOutcome(), r.getReasonCode(), r.getCreatedAt()))
                .toList();
    }

    public List<SubscriptionResponse> history(Long vehicleId, Long parkingLotId) {
        scope(vehicleId, parkingLotId);
        Instant now = now();
        return subscriptions.findByVehicleIdAndParkingLotIdOrderByStartsAtAscIdAsc(vehicleId, parkingLotId)
                .stream().map(s -> response(s, now)).toList();
    }

    /** In-term periods; this does not decide weekly coverage or barrier access. */
    public List<SubscriptionResponse> current(Long vehicleId, Long parkingLotId) {
        scope(vehicleId, parkingLotId);
        Instant now = now();
        return subscriptions.findPaidAt(vehicleId, parkingLotId, now).stream().map(s -> response(s, now)).toList();
    }

    public List<SubscriptionResponse> findPaidOverlapping(Long vehicleId, Long parkingLotId,
                                                         Instant from, Instant until) {
        scope(vehicleId, parkingLotId);
        require(from != null && until != null && from.isBefore(until), "Require from < until");
        Instant now = now();
        return subscriptions.findPaidOverlappingWithPackageSchedules(vehicleId, parkingLotId, from, until)
                .stream().map(s -> response(s, now)).toList();
    }

    /**
     * Checkout integration hook: the caller must already own a write transaction.
     * Recalculate its unsettled quote from these fresh periods before collecting money;
     * keep this vehicle lock until that transaction commits. Display reads do not lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SubscriptionResponse> lockPaidPeriodsForSettlement(Long vehicleId, Long parkingLotId,
                                                                   Instant from, Instant until) {
        require(!TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "Settlement requires an existing write transaction");
        scope(vehicleId, parkingLotId);
        require(from != null && until != null && from.isBefore(until), "Require from < until");
        lockVehicle(vehicleId);
        Instant at = now();
        List<VehicleSubscriptionEntity> found = subscriptions.findPaidOverlappingWithPackageSchedules(vehicleId, parkingLotId, from, until);
        found.forEach(entityManager::refresh);
        return found.stream().map(s -> response(s, at)).toList();
    }

    public List<SubscriptionPackageResponse> availablePackages(Long parkingLotId, VehicleType vehicleType) {
        positive(parkingLotId, "parkingLotId");
        require(vehicleType == VehicleType.CAR || vehicleType == VehicleType.MOTORBIKE,
                "Only CAR and MOTORBIKE subscription packages are supported");
        if (!packages.parkingLotExists(parkingLotId)) throw error("NOT_FOUND", "Parking lot not found", 404);
        return packages.findPublishedAt(parkingLotId, vehicleType, now()).stream()
                .map(this::packageResponse).toList();
    }

    /** Joins the link adapter's transaction so locks remain held until its intent is stored. */
    @Transactional
    public SubscriptionResponse preparePaymentLink(Long id) {
        positive(id, "subscriptionId");
        VehicleSubscriptionEntity order = lockedSubscription(id);
        require(order.getStatus() == VehicleSubscriptionStatus.PENDING_PAYMENT, "Order is not awaiting payment");
        validDeadline(order.getPaymentDeadlineAt(), now());
        require(order.getPriceVnd() != null && order.getPriceVnd() > 0,
                "Free activation requires an approved activation policy");
        return response(order, now());
    }

    /** Result persistence shares settlement's lock order, without assuming the order is still unpaid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SubscriptionResponse lockForPaymentLinkResult(Long id) {
        require(!TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "Payment link result requires an existing write transaction");
        positive(id, "subscriptionId");
        return response(lockedSubscription(id), now());
    }

    /**
     * Internal verified gateway input only; there is no browser payment-confirmation endpoint.
     * Common lock order: vehicle -> subscription -> payment. Receipt, money status and rights
     * commit together. Real transfers requiring reconciliation are retained without granting rights.
     */
    @Transactional
    public SubscriptionResponse confirmVerifiedPayment(VerifiedSubscriptionPayment v) {
        validateVerified(v);
        // receivedAt is our observation time, distinct from the provider's actual paidAt.
        v = new VerifiedSubscriptionPayment(v.subscriptionId(), v.paymentId(), v.source(), v.reference(),
                v.invoiceNo(), v.amountVnd(), v.currency(),
                v.paidAt() == null ? null : v.paidAt().truncatedTo(ChronoUnit.MICROS),
                v.receivedAt() == null ? now() : v.receivedAt().truncatedTo(ChronoUnit.MICROS), v.metadata());
        VehicleSubscriptionEntity order = lockedSubscription(v.subscriptionId());
        PaymentEntity intent = payments.lockById(v.paymentId())
                .orElseThrow(() -> error("NOT_FOUND", "Payment intent not found", 404));
        // Gateways may already have loaded the intent before waiting for the vehicle lock.
        entityManager.refresh(intent);
        validateIntent(intent, order, v);
        VehicleSubscriptionReceiptEntity replay = receipts.findBySourceAndReference(v.source(), v.reference()).orElse(null);
        if (replay != null) {
            boolean identical = Objects.equals(replay.getSubscriptionId(), v.subscriptionId())
                    && Objects.equals(replay.getPaymentId(), v.paymentId())
                    && Objects.equals(replay.getInvoiceNo(), v.invoiceNo())
                    && Objects.equals(replay.getAmountVnd(), v.amountVnd())
                    && Objects.equals(replay.getCurrency(), v.currency())
                    && Objects.equals(replay.getPaidAt(), v.paidAt());
            if (!identical) throw error("PAYMENT_REPLAY_CONFLICT", "Payment reference has conflicting data", 409);
            return response(order, now());
        }
        String reason = reviewReason(order, v, now());
        VehicleSubscriptionReceiptEntity receipt = new VehicleSubscriptionReceiptEntity();
        receipt.setSubscriptionId(order.getId());
        receipt.setPaymentId(intent.getId());
        receipt.setSource(v.source());
        receipt.setReference(v.reference());
        receipt.setInvoiceNo(v.invoiceNo());
        receipt.setCurrency(v.currency());
        receipt.setAmountVnd(v.amountVnd());
        receipt.setPaidAt(v.paidAt());
        receipt.setReceivedAt(v.receivedAt());
        receipt.setMetadata(v.metadata());
        receipt.setOutcome(reason == null ? SubscriptionReceiptOutcome.APPLIED : SubscriptionReceiptOutcome.REVIEW_REQUIRED);
        receipt.setReasonCode(reason);
        try {
            receipts.saveAndFlush(receipt);
        } catch (DataIntegrityViolationException ex) {
            // Same-order replays are serialized by the vehicle lock. A competing
            // reference belonging to a different order must fail this transaction;
            // never query/retry inside a transaction that has already failed.
            if (violatesConstraint(ex, "uq_subscription_receipt_source_reference")) {
                throw error("PAYMENT_REPLAY_CONFLICT", "Payment reference conflicts with another settlement", 409);
            }
            throw ex;
        }
        if (reason == null) {
            order.setStatus(VehicleSubscriptionStatus.PAID);
            order.setPaidAt(v.paidAt());
            order.setPaidReceiptId(receipt.getId());
        } else if (order.getStatus() == VehicleSubscriptionStatus.PENDING_PAYMENT) {
            order.setStatus(VehicleSubscriptionStatus.PAYMENT_EXCEPTION);
        }
        // The intent retains its quoted amount; receipts retain individual actual transfers.
        // Do not let legacy PAID revenue sums count a mismatched quote as actual money.
        // The receipt is the financial evidence for every review or second transfer.
        if (!"PAID".equals(intent.getStatus()) && !"REVIEW_REQUIRED".equals(intent.getStatus())) {
            intent.setStatus(reason == null ? "PAID" : "REVIEW_REQUIRED");
            intent.setPaidAt(v.paidAt() == null ? null : LocalDateTime.ofInstant(v.paidAt(), ZoneOffset.UTC));
            intent.setReferenceCode(v.reference());
            if ("SEPAY".equals(v.source())) intent.setSepayTxId(v.reference());
            intent.setResultCode(receipt.getOutcome().name());
            intent.setResultMessage(reason == null ? "Subscription period granted" : reason);
            payments.save(intent);
        }
        subscriptions.saveAndFlush(order);
        if (reason == null) {
            verifyAppliedReceipt(order, receipt, intent);
            publishAfterCommit(new SubscriptionEntitlementsChanged(order.getId(), order.getVehicleId(),
                    order.getParkingLotId(), order.getStartsAt(), order.getEndsAt(), receipt.getId()));
        }
        return response(order, now());
    }

    private String reviewReason(VehicleSubscriptionEntity order, VerifiedSubscriptionPayment v, Instant now) {
        if (order.getStatus() == VehicleSubscriptionStatus.PAID) return "SECOND_SETTLEMENT";
        if (order.getStatus() != VehicleSubscriptionStatus.PENDING_PAYMENT) return "ORDER_NOT_PENDING";
        if (!"VND".equals(v.currency())) return "CURRENCY_MISMATCH";
        if (!Objects.equals(order.getPriceVnd(), v.amountVnd())) return "AMOUNT_MISMATCH";
        if (v.paidAt() == null) return "PAYMENT_TIME_UNKNOWN";
        if (v.paidAt().isAfter(v.receivedAt()) || v.paidAt().isAfter(now)) return "PAYMENT_TIME_INVALID";
        if (v.paidAt().isAfter(order.getPaymentDeadlineAt())) return "PAID_AFTER_DEADLINE";
        if (!now.isBefore(order.getEndsAt())) return "PERIOD_ALREADY_EXPIRED";
        if (subscriptions.existsPaidOverlapping(order.getVehicleId(), order.getParkingLotId(),
                order.getStartsAt(), order.getEndsAt())) return "PAID_PERIOD_OVERLAP";
        return null;
    }

    private void validateVerified(VerifiedSubscriptionPayment v) {
        require(v != null, "Verified settlement is required");
        positive(v.subscriptionId(), "subscriptionId");
        positive(v.paymentId(), "paymentId");
        require("SEPAY".equals(v.source()) || "MEGAPAY".equals(v.source()), "Unsupported verified payment source");
        require(v.reference() != null && !v.reference().isBlank() && v.reference().length() <= 100, "Payment reference is required");
        require(!"SEPAY".equals(v.source()) || v.reference().length() <= 50,
                "SePay reference exceeds the existing payment transaction column");
        require(v.invoiceNo() != null && !v.invoiceNo().isBlank() && v.invoiceNo().length() <= 100, "Payment invoice is required");
        require(v.amountVnd() != null && v.amountVnd() >= 0, "Actual payment amount must be nonnegative");
        require(v.currency() != null && !v.currency().isBlank() && v.currency().length() <= 10, "Payment currency is required");
    }

    private void validateIntent(PaymentEntity intent, VehicleSubscriptionEntity order, VerifiedSubscriptionPayment v) {
        Long contextId = intent.getCallbackContext() == null ? null : intent.getCallbackContext().getLong("subscriptionId");
        require("SUBSCRIPTION".equals(intent.getCallbackType()) && Objects.equals(contextId, order.getId())
                        && Objects.equals(intent.getInvoiceNo(), v.invoiceNo())
                        && intent.getGateway() != null && intent.getGateway().equalsIgnoreCase(v.source()),
                "Verified settlement does not match its subscription payment intent");
        require(intent.getAmount() != null && intent.getAmount().compareTo(BigDecimal.valueOf(order.getPriceVnd())) == 0
                        && "VND".equals(intent.getCurrency()), "Payment intent differs from the frozen subscription quote");
    }

    private VehicleSubscriptionEntity lockedSubscription(Long id) {
        VehicleSubscriptionEntity before = subscription(id);
        lockVehicle(before.getVehicleId());
        if (subscriptions.lockSubscription(id).isEmpty()) throw error("NOT_FOUND", "Subscription not found", 404);
        // Re-reading alone would return the stale first-level cached object after a competing commit.
        entityManager.refresh(before);
        return before;
    }

    private void lockVehicle(Long vehicleId) {
        if (subscriptions.lockVehicle(vehicleId).isEmpty()) throw error("NOT_FOUND", "Vehicle not found", 404);
    }

    private VehicleSubscriptionEntity subscription(Long id) {
        return subscriptions.findById(id).orElseThrow(() -> error("NOT_FOUND", "Subscription not found", 404));
    }

    private SubscriptionPackageEntity salePackage(Long id, Long vehicleId, Long lotId, Instant now) {
        if (!subscriptions.parkingLotExists(lotId)) throw error("NOT_FOUND", "Parking lot not found", 404);
        String type = subscriptions.findVehicleType(vehicleId).orElseThrow(() -> error("NOT_FOUND", "Vehicle type not found", 404));
        SubscriptionPackageEntity pack = packages.findById(id).orElseThrow(() -> error("NOT_FOUND", "Subscription package not found", 404));
        if (packages.lockPackage(id).isEmpty()) throw error("NOT_FOUND", "Subscription package not found", 404);
        entityManager.refresh(pack);
        now = now();
        require(Objects.equals(pack.getParkingLotId(), lotId) && pack.getVehicleType() != null
                        && pack.getVehicleType().name().equalsIgnoreCase(type), "Package does not match the vehicle and lot");
        require(pack.getStatus() == SubscriptionPackageStatus.PUBLISHED && pack.isSaleEnabled()
                        && pack.getEffectiveFrom() != null && !now.isBefore(pack.getEffectiveFrom())
                        && (pack.getEffectiveTo() == null || now.isBefore(pack.getEffectiveTo())),
                "Package version is not currently available for purchase");
        require(pack.getPriceVnd() != null && pack.getPriceVnd() > 0, "Free activation requires an approved activation policy");
        require(pack.getDurationDays() != null && pack.getDurationDays() > 0, "Package duration must be positive");
        require(pack.getCoverageMode() != null, "Package coverage mode is required");
        return pack;
    }

    private VehicleSubscriptionEntity newOrder(String key, Long vehicleId, Long lotId,
                                              SubscriptionPackageEntity pack, Instant start, Instant deadline) {
        VehicleSubscriptionEntity order = new VehicleSubscriptionEntity();
        order.setRequestKey(key);
        order.setVehicleId(vehicleId);
        order.setParkingLotId(lotId);
        order.setSubscriptionPackage(pack);
        order.setPriceVnd(pack.getPriceVnd());
        order.setStartsAt(start);
        try {
            order.setEndsAt(start.plusSeconds(Math.multiplyExact(pack.getDurationDays().longValue(), 86_400L)));
        } catch (ArithmeticException | DateTimeException ex) {
            throw error("INVALID", "Subscription end time is out of range", 400);
        }
        order.setPaymentDeadlineAt(deadline);
        order.setStatus(VehicleSubscriptionStatus.PENDING_PAYMENT);
        return order;
    }

    private void ensureNoPaidOverlap(VehicleSubscriptionEntity order) {
        if (subscriptions.existsPaidOverlapping(order.getVehicleId(), order.getParkingLotId(), order.getStartsAt(), order.getEndsAt()))
            throw error("PERIOD_OVERLAP", "Period overlaps an existing paid subscription", 409);
    }

    private VehicleSubscriptionEntity saveOrder(VehicleSubscriptionEntity order) {
        try { return subscriptions.saveAndFlush(order); }
        catch (DataIntegrityViolationException ex) {
            if (violatesConstraint(ex, "uq_vehicle_subscription_request_key")) {
                throw error("CONFLICT", "Order conflicts with another request; retry with the same requestKey", 409);
            }
            throw ex;
        }
    }

    /** Translate only known idempotency races; FK, NOT NULL and technical failures retain their cause. */
    private boolean violatesConstraint(DataIntegrityViolationException failure, String expectedConstraint) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && expectedConstraint.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private SubscriptionResponse repeat(VehicleSubscriptionEntity existing, boolean identical) {
        if (!identical) throw error("REQUEST_KEY_CONFLICT", "requestKey was already used for a different request", 409);
        return response(existing, now());
    }

    private SubscriptionResponse response(VehicleSubscriptionEntity order, Instant now) {
        SubscriptionPackageEntity pack = order.getSubscriptionPackage();
        SubscriptionPeriodStatus state = order.getStatus() != VehicleSubscriptionStatus.PAID ? SubscriptionPeriodStatus.NONE
                : now.isBefore(order.getStartsAt()) ? SubscriptionPeriodStatus.UPCOMING
                : now.isBefore(order.getEndsAt()) ? SubscriptionPeriodStatus.IN_TERM : SubscriptionPeriodStatus.EXPIRED;
        return SubscriptionResponse.builder().id(order.getId()).vehicleId(order.getVehicleId()).parkingLotId(order.getParkingLotId())
                .packageId(pack.getId()).packageName(pack.getName()).packageCode(pack.getPackageCode()).packageVersionNo(pack.getVersionNo())
                .vehicleType(pack.getVehicleType()).registrationType(order.getRegistrationType()).previousSubscriptionId(order.getPreviousSubscriptionId())
                .priceVnd(order.getPriceVnd()).startsAt(order.getStartsAt()).endsAt(order.getEndsAt()).paymentDeadlineAt(order.getPaymentDeadlineAt())
                .status(order.getStatus()).paidAt(order.getPaidAt()).paidReceiptId(order.getPaidReceiptId())
                .periodStatus(state).coverageMode(pack.getCoverageMode())
                .durationDays(pack.getDurationDays()).schedules(schedules(pack)).createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt()).build();
    }

    private SubscriptionPackageResponse packageResponse(SubscriptionPackageEntity pack) {
        return SubscriptionPackageResponse.builder().id(pack.getId()).packageCode(pack.getPackageCode()).name(pack.getName())
                .versionNo(pack.getVersionNo()).parkingLotId(pack.getParkingLotId()).vehicleType(pack.getVehicleType()).priceVnd(pack.getPriceVnd())
                .durationDays(pack.getDurationDays()).coverageMode(pack.getCoverageMode()).effectiveFrom(pack.getEffectiveFrom())
                .effectiveTo(pack.getEffectiveTo()).saleEnabled(pack.isSaleEnabled()).status(pack.getStatus()).schedules(schedules(pack)).build();
    }

    /** Reciprocal receipt/period postcondition, before the transaction may commit. */
    private void verifyAppliedReceipt(VehicleSubscriptionEntity order,
            VehicleSubscriptionReceiptEntity receipt, PaymentEntity intent) {
        if (receipt.getId() == null || receipt.getOutcome() != SubscriptionReceiptOutcome.APPLIED
                || order.getStatus() != VehicleSubscriptionStatus.PAID
                || !Objects.equals(receipt.getSubscriptionId(), order.getId())
                || !Objects.equals(receipt.getPaymentId(), intent.getId())
                || !Objects.equals(order.getPaidReceiptId(), receipt.getId())
                || !Objects.equals(order.getPriceVnd(), receipt.getAmountVnd())
                || !Objects.equals(order.getPaidAt(), receipt.getPaidAt())
                || !Objects.equals(intent.getInvoiceNo(), receipt.getInvoiceNo())
                || !"VND".equals(receipt.getCurrency()) || !"PAID".equals(intent.getStatus())) {
            throw new IllegalStateException("Applied payment must grant its matching subscription period");
        }
    }

    private void publishAfterCommit(SubscriptionEntitlementsChanged event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            // Direct non-proxy invocations are useful in unit tests. Application
            // calls always pass through Spring's transactional service proxy.
            events.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    events.publishEvent(event);
                } catch (RuntimeException ex) {
                    // A delivery error cannot undo money/rights already committed,
                    // or make the gateway believe that this settlement failed.
                    log.error("Subscription entitlement event delivery failed after commit: subscriptionId={}",
                            event.subscriptionId(), ex);
                }
            }
        });
    }

    private List<SubscriptionScheduleResponse> schedules(SubscriptionPackageEntity pack) {
        return pack.getSchedules().stream().sorted(Comparator.comparing(SubscriptionPackageScheduleEntity::getWeekday)
                .thenComparing(SubscriptionPackageScheduleEntity::getStartTime))
                .map(s -> new SubscriptionScheduleResponse(s.getWeekday(), s.getStartTime(), s.getEndTime())).toList();
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private void scope(Long vehicleId, Long lotId) { positive(vehicleId, "vehicleId"); positive(lotId, "parkingLotId"); }
    private void validDeadline(Instant deadline, Instant now) {
        require(deadline != null && now.isBefore(deadline), "Payment deadline must be explicitly set in the future");
    }
    private void preciseTime(Instant value, String field) {
        require(value != null && value.getNano() % 1000 == 0, field + " must be explicitly set, with at most microsecond precision");
    }
    private void requestKey(String key) { require(key != null && !key.isBlank() && key.length() <= 100, "requestKey must contain 1 to 100 characters"); }
    private void positive(Long id, String field) { require(id != null && id > 0, field + " must be positive"); }
    private void require(boolean condition, String message) { if (!condition) throw error("INVALID", message, 400); }
    private BusinessException error(String code, String message, int status) { return new BusinessException(message, "SUBSCRIPTION_" + code, status); }
}
