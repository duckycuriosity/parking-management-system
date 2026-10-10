package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.ParkingPaymentResponse;
import vio.barie.core.dto.fee.ParkingFeePaymentEvidenceResponse;
import vio.barie.core.dto.fee.VerifiedParkingPayment;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.*;
import vio.barie.core.event.ParkingFeeSettled;
import vio.barie.core.repository.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Objects;

/** Fee settlement uses existing gateway intents; calculators and browsing never collect money. */
@Service
public class ParkingPaymentService {
    private static final Logger log = LoggerFactory.getLogger(ParkingPaymentService.class);
    // Existing shared payment.amount is NUMERIC(18,2): at most 16 whole VND digits.
    private static final long MAX_SHARED_PAYMENT_VND = 9_999_999_999_999_999L;
    private final ParkingFeeService fees;
    private final PaymentService payments;
    private final PaymentRepository paymentRepository;
    private final ParkingFeePaymentRepository attempts;
    private final ParkingFeeTransactionKeyRepository transactionKeys;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final String gateway;

    public ParkingPaymentService(ParkingFeeService fees, PaymentService payments, PaymentRepository paymentRepository,
            ParkingFeePaymentRepository attempts, ParkingFeeTransactionKeyRepository transactionKeys,
            EntityManager entityManager, ObjectMapper mapper, @Qualifier("feePolicyClock") Clock clock,
            ApplicationEventPublisher events, @Value("${payment.method}") String gateway) {
        this.fees = fees; this.payments = payments; this.paymentRepository = paymentRepository;
        this.attempts = attempts; this.transactionKeys = transactionKeys; this.entityManager = entityManager;
        this.mapper = mapper; this.clock = clock; this.events = events;
        this.gateway = gateway.toUpperCase(Locale.ROOT);
    }

    @Transactional
    public ParkingPaymentResponse createPaymentRequest(Long calculationId, String requestKey) {
        positive(calculationId, "calculationId");
        require(requestKey != null && !requestKey.isBlank() && requestKey.length() <= 100,
                "Request key must contain 1-100 characters", "FEE_REQUEST_KEY_INVALID", 400);
        // The same vehicle -> session -> calculation protocol serializes first requests and callbacks.
        FeeCalculationEntity calculation = fees.lockForSettlement(calculationId);
        ParkingFeePaymentEntity existing = attempts.findByRequestKey(requestKey).orElse(null);
        if (existing != null) {
            require(Objects.equals(existing.getCalculationId(), calculationId)
                    && existing.getAmountVnd() == calculation.getTotalVnd() && "VND".equals(existing.getCurrency()),
                    "Request key is already bound to different fee data", "FEE_REQUEST_KEY_CONFLICT", 409);
            return response(existing, existing.getPaymentId() == null ? null
                    : paymentRepository.findById(existing.getPaymentId()).orElseThrow(() -> error("Missing payment intent", "FEE_PAYMENT_NOT_FOUND", 409)));
        }
        payable(calculation, now());
        require(calculation.getTotalVnd() > 0, "A zero fee must use no-charge settlement", "FEE_NO_CHARGE_REQUIRED", 409);
        require(calculation.getTotalVnd() <= MAX_SHARED_PAYMENT_VND,
                "Fee exceeds the existing shared payment amount capacity", "FEE_PAYMENT_AMOUNT_LIMIT", 409);
        List<ParkingFeePaymentEntity> previousAttempts = attempts.findByCalculationIdOrderByIdAsc(calculationId);
        require(previousAttempts.stream().noneMatch(p -> "REVIEW_REQUIRED".equals(p.getStatus())),
                "Received money for this calculation requires reconciliation before another request",
                "FEE_ATTEMPT_REVIEW_REQUIRED", 409);
        require(previousAttempts.stream()
                        .noneMatch(p -> "PENDING".equals(p.getStatus()) || "PAID".equals(p.getStatus())),
                "The calculation already has an active payment request", "FEE_PAYMENT_ALREADY_REQUESTED", 409);
        ParkingSessionEntity session = session(calculation.getSessionId());
        Instant at = now();
        ParkingFeePaymentEntity attempt = new ParkingFeePaymentEntity();
        attempt.setCalculationId(calculationId); attempt.setRequestKey(requestKey);
        attempt.setAmountVnd(calculation.getTotalVnd()); attempt.setCurrency("VND");
        attempt.setRequestedAt(at); attempt.setCreatedAt(at); attempt.setUpdatedAt(at);
        attempt.setReceipt(mapper.createArrayNode());
        try { attempts.saveAndFlush(attempt); }
        catch (DataIntegrityViolationException ex) {
            if (constraint(ex, "uq_parking_fee_payment_request_key"))
                throw error("Concurrent request key conflict; reload the existing request", "FEE_REQUEST_KEY_CONFLICT", 409);
            throw ex;
        }
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setAmount(BigDecimal.valueOf(calculation.getTotalVnd()));
        request.setGoodsName("Phí gửi xe"); request.setDescription("Parking fee calculation " + calculationId);
        CreatePaymentResponse link = payments.createPaymentLink(request);
        require(link != null && "00_000".equals(link.getResultCd()) && link.getInvoiceNo() != null
                        && !link.getInvoiceNo().isBlank() && link.getInvoiceNo().length() <= 100 && link.getAmount() != null
                        && request.getAmount().compareTo(link.getAmount()) == 0
                        && (present(link.getPaymentLink()) || present(link.getQrCode())),
                "Gateway returned an invalid payment request", "FEE_GATEWAY_LINK_INVALID", 502);
        require(paymentRepository.findByInvoiceNo(link.getInvoiceNo()).isEmpty(),
                "Gateway invoice already exists", "FEE_INVOICE_CONFLICT", 409);
        PaymentEntity intent = new PaymentEntity();
        intent.setInvoiceNo(link.getInvoiceNo()); intent.setAmount(request.getAmount()); intent.setCurrency("VND");
        intent.setGateway(gateway); intent.setPaymentMethod("QR"); intent.setPayType(link.getPayType());
        intent.setDescription(request.getDescription()); intent.setStatus("PENDING");
        intent.setCallbackType(PaymentCallbackType.SESSION.name());
        JSONObject context = new JSONObject();
        context.set("calculationId", calculationId); context.set("parkingFeePaymentId", attempt.getId());
        context.set("sessionId", session.getId()); context.set("vehicleId", session.getVehicle().getId());
        context.set("parkingLotId", session.getParkingLot().getId());
        context.set("barrierId", session.getExitBarrier()); context.set("plateNumber", session.getVehicle().getPlateNumber());
        context.set("quoteValidUntil", calculation.getValidUntil().toString());
        context.set("timezone", snapshotZone(calculation).getId());
        context.set("paymentLink", link.getPaymentLink()); context.set("qrCode", link.getQrCode());
        context.set("linkExptime", link.getLinkExptime());
        intent.setCallbackContext(context);
        try { paymentRepository.saveAndFlush(intent); }
        catch (DataIntegrityViolationException ex) {
            if (constraint(ex, "uq_payment_invoice_no"))
                throw error("Gateway invoice already exists", "FEE_INVOICE_CONFLICT", 409);
            throw ex;
        }
        attempt.setPaymentId(intent.getId());
        attempts.saveAndFlush(attempt);
        return response(attempt, intent);
    }

    @Transactional
    public ParkingPaymentResponse settleNoCharge(Long calculationId) {
        positive(calculationId, "calculationId");
        FeeCalculationEntity calculation = fees.lockForSettlement(calculationId);
        require(calculation.getTotalVnd() == 0, "The calculation requires payment", "FEE_PAYMENT_REQUIRED", 409);
        if (!"SETTLED".equals(calculation.getStatus())) {
            payable(calculation, now());
            calculation.setStatus("SETTLED"); calculation.setSettledAt(now());
            entityManager.flush();
            afterCommit(new ParkingFeeSettled(calculation.getSessionId(), calculation.getId(), null, 0, calculation.getSettledAt()));
        }
        // No fake shared payment or fee-payment row is generated for a fully covered session.
        return new ParkingPaymentResponse(null, calculation.getId(), null, null, 0,
                "VND", "NO_CHARGE", "APPLIED", null, null);
    }

    @Transactional(readOnly = true)
    public List<ParkingFeePaymentEvidenceResponse> getAttempts(Long calculationId) {
        positive(calculationId, "calculationId");
        require(entityManager.find(FeeCalculationEntity.class, calculationId) != null,
                "Fee calculation not found", "FEE_CALCULATION_NOT_FOUND", 404);
        return attempts.findByCalculationIdOrderByIdAsc(calculationId).stream().map(attempt ->
                new ParkingFeePaymentEvidenceResponse(attempt.getId(), attempt.getCalculationId(), attempt.getPaymentId(),
                        attempt.getAmountVnd(), attempt.getCurrency(), attempt.getStatus(), attempt.getApplicationStatus(),
                        attempt.getApplicationReason(), attempt.getRequestedAt(), attempt.getConfirmedAt(),
                        attempt.getReceipt() == null ? mapper.createArrayNode() : attempt.getReceipt().deepCopy())).toList();
    }

    @Transactional
    public ParkingPaymentResponse confirmVerifiedPayment(VerifiedParkingPayment evidence) {
        require(evidence != null, "Payment evidence is required", "FEE_EVIDENCE_INVALID", 400);
        positive(evidence.paymentId(), "paymentId");
        PaymentEntity preview = paymentRepository.findById(evidence.paymentId())
                .orElseThrow(() -> error("Payment intent not found", "FEE_PAYMENT_NOT_FOUND", 404));
        Long calculationId = preview.getCallbackContext() == null ? null : preview.getCallbackContext().getLong("calculationId");
        positive(calculationId, "calculationId");
        // Never acquire payment first: subscription confirmation also starts with the vehicle lock.
        FeeCalculationEntity calculation = fees.lockForSettlement(calculationId);
        ParkingFeePaymentEntity attemptPreview = attempts.findByPaymentId(evidence.paymentId())
                .orElseThrow(() -> error("Fee payment attempt not found", "FEE_ATTEMPT_NOT_FOUND", 404));
        ParkingFeePaymentEntity attempt = attempts.lockById(attemptPreview.getId())
                .orElseThrow(() -> error("Fee payment attempt not found", "FEE_ATTEMPT_NOT_FOUND", 404));
        entityManager.refresh(attempt);
        PaymentEntity intent = paymentRepository.lockById(evidence.paymentId())
                .orElseThrow(() -> error("Payment intent not found", "FEE_PAYMENT_NOT_FOUND", 404));
        entityManager.refresh(intent);
        validateIntent(calculation, attempt, intent, evidence);
        String status = evidence.status() == null ? "" : evidence.status().toUpperCase(Locale.ROOT);
        require("PAID".equals(status) || "FAILED".equals(status) || "PENDING".equals(status),
                "Unrecognized verified gateway status", "FEE_EVIDENCE_INVALID", 400);
        if (!"PAID".equals(status)) {
            if (!"PAID".equals(attempt.getStatus()) && !"REVIEW_REQUIRED".equals(attempt.getStatus())) {
                attempt.setStatus(status); attempt.setUpdatedAt(now()); intent.setStatus(status);
                attempt.setApplicationStatus("FAILED".equals(status) ? "NOT_APPLIED" : "PENDING");
                attempt.setApplicationReason("FAILED".equals(status) ? "GATEWAY_FAILED" : null);
                intent.setResultCode(status); intent.setResultMessage("Verified gateway outcome");
                attempts.save(attempt); paymentRepository.save(intent);
            }
            return response(attempt, intent);
        }
        String source = evidence.source().toUpperCase(Locale.ROOT);
        require(present(evidence.reference()) && evidence.reference().length() <= 255
                        && evidence.amountVnd() != null && evidence.amountVnd() > 0,
                "Successful transfer lacks a stable reference or positive VND amount", "FEE_EVIDENCE_INVALID", 400);
        Instant receivedAt = evidence.receivedAt() == null ? now() : micros(evidence.receivedAt());
        Instant paidAt = evidence.paidAt() == null ? null : micros(evidence.paidAt());
        String identity = fingerprint(evidence, source, paidAt);
        ParkingFeeTransactionKeyId key = new ParkingFeeTransactionKeyId(source, evidence.reference());
        ParkingFeeTransactionKeyEntity duplicate = transactionKeys.findById(key).orElse(null);
        if (duplicate != null) {
            require(Objects.equals(duplicate.getAttemptId(), attempt.getId())
                            && identity.equals(duplicate.getIdentityFingerprint()),
                    "A transaction reference is bound to different payment evidence", "FEE_TRANSACTION_REFERENCE_CONFLICT", 409);
            require(attempt.getReceipt() != null && attempt.getReceipt().isArray(),
                    "Transaction registry has no receipt history", "FEE_RECEIPT_HISTORY_INVALID", 409);
            for (var observation : attempt.getReceipt()) {
                if (source.equals(observation.path("source").asText())
                        && evidence.reference().equals(observation.path("reference").asText())) {
                    return observationResponse(attempt, intent, observation.path("applicationStatus").asText(),
                            observation.path("applicationReason").isNull() ? null : observation.path("applicationReason").asText());
                }
            }
            throw error("Transaction registry has no matching observation", "FEE_RECEIPT_HISTORY_INVALID", 409);
        }
        ParkingFeeTransactionKeyEntity registry = new ParkingFeeTransactionKeyEntity();
        registry.setSource(source); registry.setReference(evidence.reference()); registry.setAttemptId(attempt.getId());
        registry.setIdentityFingerprint(identity); registry.setCreatedAt(receivedAt);
        try {
            // A concurrent different intent can race on the global reference. Constraint failure
            // aborts the whole transaction; never query a broken persistence context afterwards.
            // Assigned composite IDs must be INSERTed, not merged: merge could silently
            // reassign an identity inserted concurrently to this different fee request.
            entityManager.persist(registry);
            entityManager.flush();
        } catch (jakarta.persistence.PersistenceException | DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "parking_fee_transaction_key_pkey".equals(violation.getConstraintName())) {
                    throw error("Concurrent transaction reference conflict; retry verified callback", "FEE_TRANSACTION_REFERENCE_CONFLICT", 409);
                }
            }
            throw ex;
        }
        String reason = reviewReason(calculation, attempt, evidence.amountVnd(), paidAt, receivedAt);
        String outcome = reason == null ? "APPLIED" : "REVIEW_REQUIRED";
        appendObservation(attempt, evidence, source, paidAt, receivedAt, outcome, reason);
        attempt.setUpdatedAt(now());
        if (reason == null) {
            attempt.setStatus("PAID"); attempt.setApplicationStatus("APPLIED"); attempt.setApplicationReason(null);
            attempt.setConfirmedAt(paidAt); attempt.setPaymentSource(source); attempt.setPaymentReference(evidence.reference());
            // Existing payment timestamps in the subscription JPA path use UTC local values.
            // Preserve the full authenticated Instant in observation JSON for exact history.
            intent.setStatus("PAID"); intent.setPaidAt(paidAt.atOffset(ZoneOffset.UTC).toLocalDateTime());
            // Legacy payment.reference_code is VARCHAR(100); the full authoritative reference
            // is retained in the VARCHAR(255) fee binding, identity registry and receipt JSON.
            intent.setReferenceCode(evidence.reference().length() <= 100 ? evidence.reference() : null);
            intent.setResultCode("FEE_APPLIED"); intent.setResultMessage("Fee calculation settled");
            calculation.setStatus("SETTLED"); calculation.setSettledAt(now());
            attempts.save(attempt); paymentRepository.save(intent); entityManager.flush();
            afterCommit(new ParkingFeeSettled(calculation.getSessionId(), calculation.getId(), intent.getId(),
                    calculation.getTotalVnd(), calculation.getSettledAt()));
        } else {
            // Preserve an already completed intent; a second transfer is evidence requiring review,
            // never an instruction to settle the calculation again or overwrite the first reference.
            if (!"PAID".equals(attempt.getStatus())) {
                attempt.setStatus("REVIEW_REQUIRED"); attempt.setApplicationStatus("REVIEW_REQUIRED");
                attempt.setApplicationReason(reason); intent.setStatus("REVIEW_REQUIRED");
                intent.setResultCode(reason); intent.setResultMessage("Verified transfer requires fee reconciliation");
            }
            attempts.save(attempt); paymentRepository.save(intent);
        }
        return reason == null ? response(attempt, intent) : observationResponse(attempt, intent, "REVIEW_REQUIRED", reason);
    }

    private void validateIntent(FeeCalculationEntity calculation, ParkingFeePaymentEntity attempt,
            PaymentEntity intent, VerifiedParkingPayment evidence) {
        JSONObject context = intent.getCallbackContext();
        // Intent scope belongs to the accepted immutable snapshot. A later session-scope
        // edit supersedes the quote, but must not discard a real verified bank transfer.
        var snapshot = calculation.getFeeDetail();
        require(PaymentCallbackType.SESSION.name().equals(intent.getCallbackType()) && context != null
                        && Objects.equals(context.getLong("calculationId"), calculation.getId())
                        && Objects.equals(context.getLong("parkingFeePaymentId"), attempt.getId())
                        && Objects.equals(context.getLong("sessionId"), calculation.getSessionId())
                        && snapshot != null && snapshot.path("sessionId").isIntegralNumber()
                        && snapshot.path("vehicleId").isIntegralNumber() && snapshot.path("parkingLotId").isIntegralNumber()
                        && snapshot.path("sessionId").asLong() == calculation.getSessionId()
                        && Objects.equals(context.getLong("vehicleId"), snapshot.path("vehicleId").asLong())
                        && Objects.equals(context.getLong("parkingLotId"), snapshot.path("parkingLotId").asLong())
                        && Objects.equals(attempt.getCalculationId(), calculation.getId())
                        && Objects.equals(attempt.getPaymentId(), intent.getId())
                        && Objects.equals(intent.getInvoiceNo(), evidence.invoiceNo())
                        && "VND".equals(evidence.currency()) && "VND".equals(intent.getCurrency())
                        && "VND".equals(attempt.getCurrency()) && attempt.getAmountVnd() == calculation.getTotalVnd()
                        && intent.getAmount() != null && intent.getAmount().compareTo(BigDecimal.valueOf(attempt.getAmountVnd())) == 0
                        && present(evidence.source()) && evidence.source().length() <= 50
                        && Objects.equals(intent.getGateway(), evidence.source().toUpperCase(Locale.ROOT)),
                "Verified payment does not match its fee calculation and intent", "FEE_PAYMENT_IDENTITY_MISMATCH", 409);
    }

    private String reviewReason(FeeCalculationEntity calculation, ParkingFeePaymentEntity attempt,
            long amount, Instant paidAt, Instant receivedAt) {
        if ("PAID".equals(attempt.getStatus()) || "SETTLED".equals(calculation.getStatus())) return "ADDITIONAL_TRANSFER";
        if ("REVIEW_REQUIRED".equals(attempt.getStatus())) return "ATTEMPT_REQUIRES_REVIEW";
        if (!"QUOTED".equals(calculation.getStatus())) return "CALCULATION_SUPERSEDED";
        if (amount != attempt.getAmountVnd()) return "AMOUNT_MISMATCH";
        if (paidAt == null) return "PAYMENT_TIME_UNKNOWN";
        if (paidAt.isAfter(receivedAt)) return "PAYMENT_TIME_INVALID";
        if (calculation.getValidUntil() == null) return "QUOTE_DEADLINE_MISSING";
        if (paidAt.isAfter(calculation.getValidUntil())) return "QUOTE_EXPIRED";
        if (paidAt.isBefore(attempt.getRequestedAt())) return "PAYMENT_PRECEDES_REQUEST";
        return null;
    }

    private void appendObservation(ParkingFeePaymentEntity attempt, VerifiedParkingPayment evidence, String source,
            Instant paidAt, Instant receivedAt, String outcome, String reason) {
        require(attempt.getReceipt() == null || attempt.getReceipt().isArray(),
                "Fee receipt history has an unsupported legacy format", "FEE_RECEIPT_HISTORY_INVALID", 409);
        ArrayNode history = attempt.getReceipt() == null ? mapper.createArrayNode() : (ArrayNode) attempt.getReceipt().deepCopy();
        ObjectNode item = mapper.createObjectNode();
        item.put("source", source); item.put("reference", evidence.reference()); item.put("invoiceNo", evidence.invoiceNo());
        item.put("amountVnd", evidence.amountVnd()); item.put("currency", evidence.currency()); item.put("status", "PAID");
        if (paidAt == null) item.putNull("paidAt"); else item.put("paidAt", paidAt.toString());
        item.put("receivedAt", receivedAt.toString()); item.put("applicationStatus", outcome);
        if (reason == null) item.putNull("applicationReason"); else item.put("applicationReason", reason);
        item.set("metadata", mapper.valueToTree(evidence.metadata())); history.add(item); attempt.setReceipt(history);
    }

    private ParkingPaymentResponse response(ParkingFeePaymentEntity attempt, PaymentEntity intent) {
        CreatePaymentResponse link = null;
        if (intent != null && "PENDING".equals(intent.getStatus()) && "PENDING".equals(attempt.getStatus())
                && intent.getCallbackContext() != null) {
            link = new CreatePaymentResponse(); link.setResultCd("00_000"); link.setResultMsg("OK");
            link.setInvoiceNo(intent.getInvoiceNo()); link.setAmount(intent.getAmount()); link.setPayType(intent.getPayType());
            link.setPaymentLink(intent.getCallbackContext().getStr("paymentLink")); link.setQrCode(intent.getCallbackContext().getStr("qrCode"));
            link.setLinkExptime(intent.getCallbackContext().getStr("linkExptime"));
        }
        return new ParkingPaymentResponse(attempt.getId(), attempt.getCalculationId(), attempt.getPaymentId(),
                intent == null ? null : intent.getInvoiceNo(), attempt.getAmountVnd(), attempt.getCurrency(),
                attempt.getStatus(), attempt.getApplicationStatus(), attempt.getApplicationReason(), link);
    }

    private ParkingPaymentResponse observationResponse(ParkingFeePaymentEntity attempt, PaymentEntity intent,
            String outcome, String reason) {
        ParkingPaymentResponse result = response(attempt, intent);
        return new ParkingPaymentResponse(result.attemptId(), result.calculationId(), result.paymentId(),
                result.invoiceNo(), result.amountVnd(), result.currency(), result.status(), outcome, reason, result.gatewayLink());
    }

    private void payable(FeeCalculationEntity calculation, Instant at) {
        require("QUOTED".equals(calculation.getStatus()), "Calculation is no longer payable", "FEE_CALCULATION_NOT_PAYABLE", 409);
        require(calculation.getValidUntil() != null && !at.isAfter(calculation.getValidUntil()),
                "Quote has expired or has no explicit deadline", "FEE_QUOTE_EXPIRED", 409);
        require(calculation.getTotalVnd() >= 0, "Negative fee calculation", "FEE_CALCULATION_INVALID", 409);
    }

    private ParkingSessionEntity session(Long id) {
        ParkingSessionEntity result = entityManager.find(ParkingSessionEntity.class, id);
        require(result != null, "Parking session not found", "FEE_SESSION_NOT_FOUND", 404); return result;
    }

    private ZoneId snapshotZone(FeeCalculationEntity calculation) {
        try { return ZoneId.of(calculation.getFeeDetail().path("timezone").asText()); }
        catch (RuntimeException ex) { throw error("Calculation snapshot has no valid timezone", "FEE_TIMEZONE_MISSING", 409); }
    }

    private String fingerprint(VerifiedParkingPayment value, String source, Instant paidAt) {
        try {
            ObjectNode identity = mapper.createObjectNode().put("paymentId", value.paymentId())
                    .put("source", source).put("reference", value.reference()).put("invoiceNo", value.invoiceNo())
                    .put("amountVnd", value.amountVnd()).put("currency", value.currency());
            if (paidAt == null) identity.putNull("paidAt"); else identity.put("paidAt", paidAt.toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    identity.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void afterCommit(ParkingFeeSettled event) {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isSynchronizationActive(),
                "Fee settlement requires a transaction", "FEE_TRANSACTION_REQUIRED", 500);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { events.publishEvent(event); }
                catch (RuntimeException ex) { log.error("Fee settlement event failed after commit: calculationId={}", event.calculationId(), ex); }
            }
        });
    }
    private Instant now() { return micros(clock.instant()); }
    private Instant micros(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private boolean constraint(Throwable failure, String name) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && name.equals(violation.getConstraintName())) return true;
        return false;
    }
    private void positive(Long id, String field) { require(id != null && id > 0, field + " must be positive", "FEE_ID_INVALID", 400); }
    private void require(boolean valid, String message, String code, int status) { if (!valid) throw error(message, code, status); }
    private BusinessException error(String message, String code, int status) { return new BusinessException(message, code, status); }
}
