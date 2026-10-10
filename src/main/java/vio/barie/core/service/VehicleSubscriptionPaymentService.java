package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.common.PaymentType;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.service.payment.common.PaymentLinkRejectedException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A QR is an unpaid intent. Its invoice and routing commit before gateway IO.
 * Short transactions serialize claims and result updates; no lock spans a network call.
 */
@Service
public class VehicleSubscriptionPaymentService {
    private static final String CREATING = "CREATING";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String READY = "READY";
    private static final String REJECTED = "REJECTED";

    private final VehicleSubscriptionService subscriptions;
    private final PaymentService payments;
    private final PaymentRepository repository;
    private final Clock clock;
    private final MegaPayConfig megaPayConfig;
    private final SePayConfig sePayConfig;
    private final String gatewayMethod;
    private final TransactionTemplate transaction;

    public VehicleSubscriptionPaymentService(VehicleSubscriptionService subscriptions, PaymentService payments,
            PaymentRepository repository, @Qualifier("feePolicyClock") Clock clock,
            MegaPayConfig megaPayConfig, SePayConfig sePayConfig, @Value("${payment.method}") String gatewayMethod,
            PlatformTransactionManager transactionManager) {
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.repository = repository;
        this.clock = clock;
        this.megaPayConfig = megaPayConfig;
        this.sePayConfig = sePayConfig;
        this.gatewayMethod = gatewayMethod;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Registration must be committed before invoking this action. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreatePaymentResponse createPaymentLink(Long subscriptionId) {
        int expiryMinutes = expiryMinutes();
        Claim claim = transaction.execute(status -> claim(subscriptionId, expiryMinutes));
        if (claim.cached() != null) return claim.cached();

        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setAmount(claim.amount());
        request.setGoodsName("Đăng ký thuê bao gửi xe");
        request.setDescription("Vehicle subscription " + subscriptionId);
        CreatePaymentResponse response;
        try {
            response = payments.createPaymentLink(request, claim.invoice());
        } catch (PaymentLinkRejectedException ex) {
            recordFailure(claim, REJECTED);
            throw failure("Gateway từ chối tạo QR; có thể thử lại", "SUBSCRIPTION_PAYMENT_LINK_REJECTED", 502);
        } catch (RuntimeException ex) {
            // Timeout, malformed response or lost connection might have created the invoice.
            // No automatic replay is allowed without a verified gateway recovery contract.
            recordFailure(claim, UNKNOWN);
            throw unknown();
        }
        if (!validResponse(response, claim)) {
            recordFailure(claim, UNKNOWN);
            throw unknown();
        }
        boolean pending = Boolean.TRUE.equals(transaction.execute(status -> finish(claim, response)));
        if (!pending) throw alreadyProcessed();
        return response;
    }

    private Claim claim(Long subscriptionId, int expiryMinutes) {
        // Shared lock order: vehicle -> subscription. All concurrent QR claims for this order wait here.
        SubscriptionResponse subscription = subscriptions.preparePaymentLink(subscriptionId);
        Instant now = clock.instant();
        BigDecimal amount = BigDecimal.valueOf(subscription.getPriceVnd());
        PaymentEntity previous = repository.findLatestPendingBySubscriptionId(subscriptionId.toString()).orElse(null);
        if (previous != null) {
            if (previous.getAmount() == null || amount.compareTo(previous.getAmount()) != 0
                    || !"VND".equals(previous.getCurrency())
                    || !gatewayMethod.equalsIgnoreCase(previous.getGateway())) {
                throw failure("Payment hiện có không khớp đơn hoặc gateway", "SUBSCRIPTION_PAYMENT_INTENT_CONFLICT", 409);
            }
            JSONObject context = previous.getCallbackContext();
            String state = context == null ? null : context.getStr("linkState");
            if (CREATING.equals(state)) {
                throw failure("QR đang được tạo; không tạo thêm invoice", "SUBSCRIPTION_PAYMENT_LINK_IN_PROGRESS", 409);
            }
            if (UNKNOWN.equals(state)) throw unknown();
            if (usableLink(previous, now)) {
                return new Claim(subscriptionId, previous.getId(), previous.getInvoiceNo(), amount, responseFrom(previous));
            }
            // An old known QR may be replaced after expiry; its payment remains for late transfers.
            // Missing/corrupt metadata is ambiguous, rather than evidence that creation failed.
            if (!knownExpiredLink(previous, now)) throw unknown();
        }
        Instant expiresAt = now.plusSeconds(expiryMinutes * 60L);
        if (subscription.getPaymentDeadlineAt().isBefore(expiresAt)) expiresAt = subscription.getPaymentDeadlineAt();
        JSONObject context = new JSONObject();
        context.set("subscriptionId", subscriptionId);
        context.set("linkState", CREATING);
        context.set("linkAttemptAt", now.toString());
        context.set("linkExpiresAt", expiresAt.toString());
        PaymentEntity intent = new PaymentEntity();
        intent.setInvoiceNo(newInvoice());
        intent.setAmount(amount);
        intent.setCurrency("VND");
        intent.setStatus(PaymentType.PENDING.name());
        intent.setPaymentMethod("QR");
        intent.setPayType("QR");
        intent.setDescription("Vehicle subscription " + subscriptionId);
        intent.setCallbackType(PaymentCallbackType.SUBSCRIPTION.name());
        intent.setCallbackContext(context);
        intent.setGateway(gatewayMethod.toUpperCase(Locale.ROOT));
        try {
            repository.saveAndFlush(intent);
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "uq_payment_invoice_no".equals(violation.getConstraintName())) {
                    throw failure("Invoice bị trùng; chưa gọi gateway", "SUBSCRIPTION_INVOICE_CONFLICT", 409);
                }
            }
            throw ex;
        }
        return new Claim(subscriptionId, intent.getId(), intent.getInvoiceNo(), amount, null);
    }

    private boolean finish(Claim claim, CreatePaymentResponse response) {
        SubscriptionResponse subscription = subscriptions.lockForPaymentLinkResult(claim.subscriptionId());
        PaymentEntity intent = lockedIntent(claim);
        // Early callbacks may already have atomically settled this order. Never save a detached
        // pre-call entity or overwrite receipt outcome/status/result metadata with QR response data.
        if (!PaymentType.PENDING.name().equals(intent.getStatus())) return false;
        if (subscription.getStatus() != VehicleSubscriptionStatus.PENDING_PAYMENT
                || !subscription.getPaymentDeadlineAt().isAfter(clock.instant())) {
            // Another retained invoice may have paid this order during replacement creation.
            // Keep this new intent routable for a late transfer, but never expose its QR.
            intent.getCallbackContext().set("linkState", "OBSOLETE");
            repository.saveAndFlush(intent);
            return false;
        }
        JSONObject context = intent.getCallbackContext();
        context.set("linkState", READY);
        context.set("paymentLink", response.getPaymentLink());
        context.set("qrCode", response.getQrCode());
        context.set("linkExptime", response.getLinkExptime());
        intent.setPayType(response.getPayType());
        repository.saveAndFlush(intent);
        return true;
    }

    private void recordFailure(Claim claim, String state) {
        boolean pending = Boolean.TRUE.equals(transaction.execute(status -> {
            PaymentEntity intent = lockedIntent(claim);
            if (!PaymentType.PENDING.name().equals(intent.getStatus())) return false;
            intent.getCallbackContext().set("linkState", state);
            intent.setResultCode("QR_" + state);
            intent.setResultMessage(REJECTED.equals(state) ? "Gateway rejected QR creation" : "QR creation outcome unknown");
            if (REJECTED.equals(state)) intent.setStatus(PaymentType.FAILED.name());
            repository.saveAndFlush(intent);
            return true;
        }));
        if (!pending) throw alreadyProcessed();
    }

    private PaymentEntity lockedIntent(Claim claim) {
        PaymentEntity intent = repository.lockById(claim.paymentId())
                .orElseThrow(() -> failure("Không tìm thấy payment đã lưu", "SUBSCRIPTION_PAYMENT_INTENT_MISSING", 409));
        if (!claim.invoice().equals(intent.getInvoiceNo())
                || !PaymentCallbackType.SUBSCRIPTION.name().equals(intent.getCallbackType())
                || intent.getCallbackContext() == null
                || !Objects.equals(claim.subscriptionId(), intent.getCallbackContext().getLong("subscriptionId"))
                || intent.getAmount() == null || claim.amount().compareTo(intent.getAmount()) != 0
                || !"VND".equals(intent.getCurrency())) {
            throw failure("Payment không khớp yêu cầu tạo QR", "SUBSCRIPTION_PAYMENT_INTENT_CONFLICT", 409);
        }
        return intent;
    }

    private int expiryMinutes() {
        int minutes = switch (gatewayMethod) {
            case "sepay" -> sePayConfig.getQrExpireMinutes();
            case "megapay" -> megaPayConfig.getQrExpireMinutes();
            default -> throw failure("Payment gateway chưa được hỗ trợ", "SUBSCRIPTION_GATEWAY_UNSUPPORTED", 500);
        };
        if (minutes <= 0) throw failure("Thời hạn QR phải lớn hơn 0 phút", "SUBSCRIPTION_QR_CONFIG_INVALID", 500);
        if ("sepay".equals(gatewayMethod)
                && (sePayConfig.getInvoicePrefix() == null || sePayConfig.getInvoicePrefix().isBlank())) {
            throw failure("Thiếu tiền tố invoice SePay", "SUBSCRIPTION_QR_CONFIG_INVALID", 500);
        }
        return minutes;
    }

    private String newInvoice() {
        String random = UUID.randomUUID().toString().replace("-", "");
        // SePay's existing webhook parser recognizes configured prefix followed by digits.
        return ("sepay".equals(gatewayMethod) ? sePayConfig.getInvoicePrefix() : "viotech_")
                + new BigInteger(random, 16);
    }

    private boolean validResponse(CreatePaymentResponse response, Claim claim) {
        return response != null && "00_000".equals(response.getResultCd())
                && claim.invoice().equals(response.getInvoiceNo())
                && response.getAmount() != null && claim.amount().compareTo(response.getAmount()) == 0
                && hasLink(response.getPaymentLink(), response.getQrCode());
    }

    private boolean usableLink(PaymentEntity payment, Instant now) {
        JSONObject context = payment.getCallbackContext();
        if (context == null) return false;
        try {
            return Instant.parse(context.getStr("linkExpiresAt")).isAfter(now)
                    && hasLink(context.getStr("paymentLink"), context.getStr("qrCode"));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean knownExpiredLink(PaymentEntity payment, Instant now) {
        JSONObject context = payment.getCallbackContext();
        if (context == null || !hasLink(context.getStr("paymentLink"), context.getStr("qrCode"))) return false;
        try {
            return !Instant.parse(context.getStr("linkExpiresAt")).isAfter(now);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean hasLink(String paymentLink, String qrCode) {
        return paymentLink != null && !paymentLink.isBlank() || qrCode != null && !qrCode.isBlank();
    }

    private CreatePaymentResponse responseFrom(PaymentEntity payment) {
        CreatePaymentResponse response = new CreatePaymentResponse();
        response.setResultCd("00_000");
        response.setResultMsg("OK");
        response.setInvoiceNo(payment.getInvoiceNo());
        response.setAmount(payment.getAmount());
        response.setPayType(payment.getPayType());
        response.setLinkExptime(payment.getCallbackContext().getStr("linkExptime"));
        response.setPaymentLink(payment.getCallbackContext().getStr("paymentLink"));
        response.setQrCode(payment.getCallbackContext().getStr("qrCode"));
        return response;
    }

    private BusinessException unknown() {
        return failure("Chưa xác định được kết quả tạo QR; cần đối soát invoice hiện có",
                "SUBSCRIPTION_PAYMENT_LINK_UNKNOWN", 503);
    }

    private BusinessException alreadyProcessed() {
        return failure("Payment đã có kết quả xác minh; tải lại trạng thái đơn",
                "SUBSCRIPTION_PAYMENT_ALREADY_PROCESSED", 409);
    }

    private BusinessException failure(String message, String code, int status) {
        return new BusinessException(message, code, status);
    }

    private record Claim(Long subscriptionId, Long paymentId, String invoice, BigDecimal amount, CreatePaymentResponse cached) {}
}
