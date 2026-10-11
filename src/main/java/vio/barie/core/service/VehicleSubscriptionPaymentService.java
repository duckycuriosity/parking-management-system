package vio.barie.core.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;
import vio.barie.core.service.payment.common.PaymentGateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Uses the existing payment context and inline subscription receipt; no schema additions. */
@Service
@RequiredArgsConstructor
public class VehicleSubscriptionPaymentService {
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;
    private final PaymentGateway gateway;
    private static final DateTimeFormatter LINK_EXPIRY = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public CreatePaymentResponse createPaymentLink(Long ownerId, Long subscriptionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ServiceException("Create the payment link after registration has committed", 409);
        }
        Intent intent = transaction(() -> prepare(ownerId, subscriptionId));
        if (intent.response() != null) return intent.response();
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setInvoiceNo(intent.invoiceNo());
        request.setAmount(intent.amount());
        request.setGoodsName("Subscription " + subscriptionId);
        request.setDescription("Subscription " + subscriptionId);
        CreatePaymentResponse response;
        try {
            // The invoice is already committed. No transaction or row lock crosses this call.
            response = gateway.createPaymentLink(request);
        } catch (RuntimeException exception) {
            transaction(() -> {
                PaymentEntity payment = lockedPayment(intent.paymentId());
                boolean rejected = !gateway.createsLinkRemotely()
                        || exception instanceof ServiceException serviceException && serviceException.getStatusCode() == 400;
                payment.getCallbackContext().set("qrState", rejected ? "REJECTED" : "UNKNOWN");
                return null;
            });
            throw exception;
        }
        if (response == null || !intent.invoiceNo().equals(response.getInvoiceNo())
                || response.getAmount() == null || intent.amount().compareTo(response.getAmount()) != 0
                || !"00_000".equals(response.getResultCd())) {
            transaction(() -> { lockedPayment(intent.paymentId()).getCallbackContext().set("qrState", "UNKNOWN"); return null; });
            throw new ServiceException("Gateway result does not match the saved invoice; reconciliation required", 409);
        }
        transaction(() -> {
            PaymentEntity payment = lockedPayment(intent.paymentId());
            JSONObject context = payment.getCallbackContext();
            // A callback may already have settled the payment. Only update QR metadata.
            context.set("qrState", "READY");
            context.set("qr", responseMetadata(response));
            if (response.getPayType() != null) payment.setPayType(response.getPayType());
            return null;
        });
        return response;
    }

    private Intent prepare(Long ownerId, Long subscriptionId) {
        VehicleSubscriptionEntity subscription = lockedSubscription(subscriptionId);
        VehicleEntity vehicle = entityManager.find(VehicleEntity.class, subscription.getVehicleId());
        if (vehicle == null || !Objects.equals(ownerId, vehicle.getOwnerId())) {
            throw new ServiceException("Subscription does not belong to this owner", 404);
        }
        if (!"PENDING_PAYMENT".equals(subscription.getStatus())) {
            throw new ServiceException("Subscription is not awaiting payment", 409);
        }
        if (subscription.getPriceVnd() == null || subscription.getPriceVnd() <= 0) {
            throw new ServiceException("A bank payment requires a positive subscription price", 409);
        }
        if (subscription.getPaymentDeadlineAt() == null
                || OffsetDateTime.now().isAfter(subscription.getPaymentDeadlineAt())) {
            throw new ServiceException("Subscription payment deadline has passed", 409);
        }
        List<PaymentEntity> previous = entityManager.createNativeQuery("""
                SELECT * FROM payment WHERE callback_type = 'SUBSCRIPTION'
                AND callback_context->>'subscriptionId' = :subscriptionId ORDER BY id DESC
                """, PaymentEntity.class).setParameter("subscriptionId", subscriptionId.toString())
                .setMaxResults(1).getResultList();
        if (!previous.isEmpty()) {
            PaymentEntity payment = lockedPayment(previous.getFirst().getId());
            JSONObject context = payment.getCallbackContext();
            if (!gateway.gatewayName().equals(payment.getGateway())) {
                throw new ServiceException("Existing invoice belongs to another gateway; reconciliation required", 409);
            }
            if ("REJECTED".equals(context.getStr("qrState"))) {
                context.set("qrState", "CREATING");
                return new Intent(payment.getId(), payment.getInvoiceNo(), payment.getAmount(), null);
            }
            if (!"READY".equals(context.getStr("qrState"))) {
                throw new ServiceException("Previous gateway result is unresolved; reuse its invoice for reconciliation", 409);
            }
            CreatePaymentResponse response = JSONUtil.toBean(context.getJSONObject("qr"), CreatePaymentResponse.class);
            if (response.getLinkExptime() == null || !linkExpired(response.getLinkExptime())) {
                return new Intent(payment.getId(), payment.getInvoiceNo(), payment.getAmount(), response);
            }
            // Keep the expired intent so a late transfer still resolves to the original order.
        }
        PaymentEntity payment = new PaymentEntity();
        payment.setAmount(BigDecimal.valueOf(subscription.getPriceVnd()));
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setPayType("QR");
        payment.setGateway(gateway.gatewayName());
        payment.setStatus("PENDING");
        payment.setDescription("Subscription " + subscriptionId);
        payment.setCallbackType("SUBSCRIPTION");
        payment.setCallbackContext(new JSONObject().set("subscriptionId", subscriptionId).set("qrState", "CREATING"));
        entityManager.persist(payment);
        entityManager.flush();
        // Existing identity sequence provides uniqueness without introducing a counter or schema constraint.
        payment.setInvoiceNo(gateway.generateInvoiceNo(payment.getId()));
        return new Intent(payment.getId(), payment.getInvoiceNo(), payment.getAmount(), null);
    }

    /** Called only after the gateway adapter has verified the callback source/signature. */
    public void applyVerifiedPayment(String invoiceNo, String source, String reference, BigDecimal amount,
                                     String currency, OffsetDateTime paidAt, String resultCode, String resultMessage) {
        if (source == null || source.isBlank() || source.length() > 50 || reference == null || reference.isBlank()
                || reference.length() > ("SEPAY".equals(source) ? 50 : 100)) {
            throw new ServiceException("Verified payment is missing a transaction reference", 400);
        }
        transaction(() -> {
            entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(hashtextextended(:key, 0)) AS text)")
                    .setParameter("key", source + ":" + reference).getSingleResult();
            List<PaymentEntity> matches = entityManager.createQuery(
                    "select p from PaymentEntity p where p.invoiceNo = :invoiceNo", PaymentEntity.class)
                    .setParameter("invoiceNo", invoiceNo).getResultList();
            if (matches.size() != 1) throw new ServiceException("Payment invoice not found", 404);
            PaymentEntity candidate = matches.getFirst();
            if (!"SUBSCRIPTION".equals(candidate.getCallbackType()) || candidate.getCallbackContext() == null) {
                throw new ServiceException("Payment is not a subscription invoice", 409);
            }
            Long subscriptionId = candidate.getCallbackContext().getLong("subscriptionId");
            VehicleSubscriptionEntity subscription = lockedSubscription(subscriptionId);
            PaymentEntity payment = lockedPayment(candidate.getId());
            JSONObject identity = new JSONObject().set("source", source).set("reference", reference);
            List<?> duplicates = entityManager.createNativeQuery("""
                    SELECT item::text FROM vehicle_subscription s
                    CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(s.payment_receipt->'transactions') = 'array'
                        THEN s.payment_receipt->'transactions' ELSE '[]'::jsonb END) item
                    WHERE item->>'source' = :source AND item->>'reference' = :reference
                    """).setParameter("source", source).setParameter("reference", reference).getResultList();
            if (!duplicates.isEmpty()) {
                JSONObject existing = JSONUtil.parseObj(duplicates.getFirst().toString());
                if (!sameEvidence(existing, invoiceNo, amount, currency, paidAt))
                    throw new ServiceException("Transaction reference was already recorded with different evidence", 409);
                return null;
            }
            String reason = validationFailure(subscription, payment, source, amount, currency, paidAt);
            if (reason == null && entityManager.createQuery("""
                    select count(s.id) from VehicleSubscriptionEntity s where s.vehicleId = :vehicle
                    and s.parkingLotId = :lot and s.status = 'PAID' and s.id <> :id
                    and s.startsAt < :ends and s.endsAt > :starts
                    """, Long.class).setParameter("vehicle", subscription.getVehicleId())
                    .setParameter("lot", subscription.getParkingLotId()).setParameter("id", subscription.getId())
                    .setParameter("starts", subscription.getStartsAt()).setParameter("ends", subscription.getEndsAt())
                    .getSingleResult() > 0) reason = "OVERLAPPING_PAID_PERIOD";
            JSONObject observation = new JSONObject(identity).set("invoiceNo", invoiceNo)
                    .set("amount", amount).set("currency", currency)
                    .set("paidAt", paidAt == null ? null : paidAt.toString())
                    .set("applicationStatus", reason == null ? "APPLIED" : "REVIEW")
                    .set("reason", reason).set("gatewayResultCode", resultCode)
                    .set("gatewayResultMessage", resultMessage);
            JSONObject receipt = receiptEnvelope(subscription.getPaymentReceipt());
            receipt.getJSONArray("transactions").put(observation);
            subscription.setPaymentReceipt(receipt.toString());
            if (reason == null) {
                subscription.setStatus("PAID");
                subscription.setPaidAt(paidAt);
                subscription.setPaymentSource(source);
                subscription.setPaymentReference(reference);
                payment.setStatus("PAID");
                payment.setPaidAt(paidAt.toLocalDateTime());
                payment.setReferenceCode(reference);
                if ("SEPAY".equals(source)) payment.setSepayTxId(reference);
                payment.setResultCode(resultCode);
                payment.setResultMessage(resultMessage == null ? null : resultMessage.substring(0, Math.min(500, resultMessage.length())));
            } else if (!"PAID".equals(payment.getStatus())) {
                payment.setResultCode("REVIEW");
                payment.setResultMessage(reason);
            }
            return null;
        });
    }

    static String validationFailure(VehicleSubscriptionEntity subscription, PaymentEntity payment,
                                    String source, BigDecimal amount, String currency, OffsetDateTime paidAt) {
        if (!Objects.equals(payment.getGateway(), source)) return "GATEWAY_MISMATCH";
        if (!"VND".equals(currency) || !"VND".equals(payment.getCurrency())) return "CURRENCY_MISMATCH";
        if (amount == null || payment.getAmount() == null || subscription.getPriceVnd() == null
                || amount.signum() <= 0 || amount.compareTo(payment.getAmount()) != 0
                || amount.compareTo(BigDecimal.valueOf(subscription.getPriceVnd())) != 0) return "AMOUNT_MISMATCH";
        if (paidAt == null) return "MISSING_TRUSTED_PAID_AT";
        if (subscription.getCreatedAt() == null || paidAt.isBefore(subscription.getCreatedAt())) return "PAYMENT_BEFORE_ORDER";
        if (paidAt.isAfter(OffsetDateTime.now())) return "PAYMENT_TIME_IN_FUTURE";
        if (subscription.getPaymentDeadlineAt() == null) return "MISSING_PAYMENT_DEADLINE";
        if (paidAt.isAfter(subscription.getPaymentDeadlineAt())) {
            return "PAYMENT_AFTER_DEADLINE";
        }
        if (subscription.getEndsAt() == null || !OffsetDateTime.now().isBefore(subscription.getEndsAt())) return "PERIOD_EXPIRED";
        if (!"PENDING_PAYMENT".equals(subscription.getStatus())) return "ORDER_NOT_PENDING_PAYMENT";
        return null;
    }

    private VehicleSubscriptionEntity lockedSubscription(Long id) {
        if (id == null) throw new ServiceException("Missing subscriptionId in payment context", 409);
        VehicleSubscriptionEntity subscription = entityManager.find(VehicleSubscriptionEntity.class, id);
        if (subscription == null) throw new ServiceException("Subscription not found", 404);
        if (entityManager.find(VehicleEntity.class, subscription.getVehicleId(), LockModeType.PESSIMISTIC_WRITE) == null)
            throw new ServiceException("Subscription vehicle not found", 404);
        entityManager.refresh(subscription, LockModeType.PESSIMISTIC_WRITE);
        return subscription;
    }

    static boolean sameEvidence(JSONObject existing, String invoice, BigDecimal amount, String currency, OffsetDateTime paidAt) {
        String priorTime = existing.getStr("paidAt");
        return Objects.equals(invoice, existing.getStr("invoiceNo")) && Objects.equals(currency, existing.getStr("currency"))
                && (amount == null ? existing.getBigDecimal("amount") == null
                    : existing.getBigDecimal("amount") != null && amount.compareTo(existing.getBigDecimal("amount")) == 0)
                && (paidAt == null ? priorTime == null : priorTime != null && paidAt.toInstant().equals(OffsetDateTime.parse(priorTime).toInstant()));
    }

    private PaymentEntity lockedPayment(Long id) {
        PaymentEntity payment = entityManager.find(PaymentEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (payment == null) throw new ServiceException("Payment not found", 404);
        // A pre-lock query can have placed an old version in this transaction's persistence context.
        entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
        return payment;
    }

    static JSONObject receiptEnvelope(String existing) {
        JSONObject receipt = new JSONObject();
        if (existing != null && !existing.isBlank()) {
            if (JSONUtil.isTypeJSONObject(existing)) {
                JSONObject parsed = JSONUtil.parseObj(existing);
                if (parsed.get("transactions") instanceof JSONArray) return parsed;
                receipt.set("legacyReceipt", parsed);
            } else receipt.set("legacyReceipt", existing);
        }
        return receipt.set("transactions", new JSONArray());
    }

    private static JSONObject responseMetadata(CreatePaymentResponse response) {
        return new JSONObject().set("invoiceNo", response.getInvoiceNo()).set("amount", response.getAmount())
                .set("payType", response.getPayType()).set("linkExptime", response.getLinkExptime())
                .set("paymentLink", response.getPaymentLink()).set("qrCode", response.getQrCode())
                .set("resultCd", response.getResultCd()).set("resultMsg", response.getResultMsg());
    }

    static boolean linkExpired(String expiry) {
        try { return !LocalDateTime.now().isBefore(LocalDateTime.parse(expiry, LINK_EXPIRY)); }
        catch (RuntimeException ignored) {
            throw new ServiceException("Stored QR expiry cannot be verified; reconciliation required", 409);
        }
    }

    private <T> T transaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private record Intent(Long paymentId, String invoiceNo, BigDecimal amount, CreatePaymentResponse response) {}
}
