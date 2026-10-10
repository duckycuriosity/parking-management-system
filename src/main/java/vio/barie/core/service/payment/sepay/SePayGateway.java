package vio.barie.core.service.payment.sepay;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.common.PaymentType;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.*;
import vio.barie.core.dto.subscription.VerifiedSubscriptionPayment;
import vio.barie.core.dto.fee.VerifiedParkingPayment;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.CalculatorService;
import vio.barie.core.service.VehicleSubscriptionService;
import vio.barie.core.service.ParkingPaymentService;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentGateway;
import vio.barie.core.service.payment.common.PaymentLinkRejectedException;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class SePayGateway implements PaymentGateway {

    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final SePayConfig sepayConfig;
    private final RestClient restClient = RestClient.create();
    private final ParkingMessageService ioTService;
    private final CalculatorService calculatorService;
    private final VehicleSubscriptionService vehicleSubscriptionService;
    private ObjectProvider<ParkingPaymentService> parkingPayments;

    public void setParkingPayments(ObjectProvider<ParkingPaymentService> parkingPayments) {
        this.parkingPayments = parkingPayments;
    }

    private static final DateTimeFormatter SEPAY_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // TẠO QR
    public CreatePaymentResponse createPaymentLink(CreatePaymentRequest req) {
        return createPaymentLink(req, generateInvoiceNo());
    }

    @Override
    public CreatePaymentResponse createPaymentLink(CreatePaymentRequest req, String invoiceNo) {
        if (req.getAmount() == null || req.getAmount().signum() <= 0) {
            throw new PaymentLinkRejectedException("Số tiền phải > 0", 400);
        }
        if (sepayConfig.getBankAccount() == null || sepayConfig.getBankAccount().isBlank()) {
            throw new PaymentLinkRejectedException("Chưa cấu hình sepay.bank-account", 500);
        }
        if (invoiceNo == null || invoiceNo.isBlank()) {
            throw new PaymentLinkRejectedException("Thiếu mã thanh toán đã lưu", 400);
        }
        String qrUrl = buildVietQrUrl(
                sepayConfig.getBankAccount(),
                sepayConfig.getBankBrand(),
                req.getAmount(),
                invoiceNo);

        String linkExptime = LocalDateTime.now()
                .plusMinutes(sepayConfig.getQrExpireMinutes())
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        CreatePaymentResponse resp = new CreatePaymentResponse();
        resp.setInvoiceNo(invoiceNo);
        resp.setAmount(req.getAmount());
        resp.setPayType(req.getPayType() != null ? req.getPayType() : "QR");
        resp.setLinkExptime(linkExptime);
        // sepay ko ho tro base64
        resp.setQrCode(null);
        resp.setPaymentLink(qrUrl);
        resp.setResultCd("00_000");
        resp.setResultMsg("OK");
        // do chỉ dùng webhook nên ko có merid -> tạm thời set là bank account
        resp.setMerId(sepayConfig.getBankAccount());
        resp.setTimeStamp(String.valueOf(System.currentTimeMillis()));
        return resp;
    }

    private String buildVietQrUrl(String acc, String bank, BigDecimal amount, String invoiceNo) {
        String des = URLEncoder.encode(invoiceNo, StandardCharsets.UTF_8);
        return UriComponentsBuilder.fromUriString(sepayConfig.getQrBaseUrl())
                .queryParam("acc", acc)
                .queryParam("bank", bank)
                .queryParam("amount", amount.stripTrailingZeros().toPlainString())
                .queryParam("des", des)
                .build(true)
                .toUriString();
    }

    // WEBHOOK
    @Transactional
    public void handleCallback(String str) {
        SePayWebhookPayload payload = JSONUtil.toBean(str, SePayWebhookPayload.class);
        if (payload == null || payload.getId() == null) {
            log.warn("SePay webhook: payload thiếu id, bỏ qua");
            return;
        }

        // (1) Idempotency
//        if (paymentRepository.existsBySepayTxId(payload.getId())) {
//            log.info("SePay webhook: tx id={} đã xử lý, bỏ qua", payload.getId());
//            return;
//        }

        // (2) Chỉ xử lý tiền vào
        if (!"in".equalsIgnoreCase(payload.getTransferType())) {
            log.debug("SePay webhook: bỏ qua transferType={}", payload.getTransferType());
            return;
        }

        // (3) Parse invoiceNo
        String codeInvoice = extractInvoiceNo(payload.getCode());
        String invoiceNo = codeInvoice != null ? codeInvoice : extractInvoiceNo(payload.getContent());
        if (invoiceNo == null) {
            log.warn("SePay webhook: không tìm thấy invoiceNo trong content='{}', payload={}",
                    payload.getContent(), payload);
            return;
        }

        // (4) Tìm payment + so khớp số tiền
        PaymentEntity payment = paymentRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ServiceException(
                        "SePay webhook: không tìm thấy payment invoiceNo=" + invoiceNo, 404));

        // Subscription settlement owns locking, receipt deduplication and entitlement grants.
        // Never run legacy PAID/TICKET/IoT mutations for this callback type.
        if (PaymentCallbackType.SUBSCRIPTION.name().equals(payment.getCallbackType())) {
            settleSubscription(payment, payload);
            return;
        }
        if (isParkingFeeIntent(payment)) {
            settleParkingFee(payment, payload);
            return;
        }

//        if (payment.getAmount() != null && payload.getTransferAmount() != null
//                && payment.getAmount().compareTo(payload.getTransferAmount()) != 0) {
//            log.warn("SePay webhook: lệch số tiền invoice={} expected={} actual={}",
//                    invoiceNo, payment.getAmount(), payload.getTransferAmount());
//            payment.setStatus(PaymentType.FRAUD.name());
//            payment.setResultMessage("Amount mismatch: expected="
//                    + payment.getAmount() + " actual=" + payload.getTransferAmount());
//            payment.setSepayTxId(payload.getId());
//            payment.setGateway(payload.getGateway());
//            payment.setReferenceCode(payload.getReferenceCode());
//            paymentRepository.save(payment);
//            return;
//        }

        if (PaymentType.PAID.name().equals(payment.getStatus())) {
            log.info("SePay webhook: invoice {} đã PAID, chỉ gán sepay_tx_id và bỏ qua nghiệp vụ",
                    invoiceNo);
            payment.setSepayTxId(payload.getId());
            paymentRepository.save(payment);
            return;
        }

        if (PaymentCallbackType.SESSION.name().equals(payment.getCallbackType())) {
            JSONObject context = payment.getCallbackContext();
            ioTService.publishPaymentConfirmed(
                    context.getStr("barrierId"),
                    context.getLong("sessionId"),
                    payload.getTransferAmount(),
                    invoiceNo);
        }

        payment.setStatus(PaymentType.PAID.name());
        payment.setPaidAt(parseSePayDate(payload.getTransactionDate()));
        payment.setSepayTxId(payload.getId());
        payment.setGateway(payload.getGateway());
        payment.setReferenceCode(payload.getReferenceCode());
        payment.setResultCode("OK");
        payment.setResultMessage(payload.getDescription());
        paymentRepository.save(payment);

        log.info("SePay webhook processed: invoiceNo={}, txId={}, amount={}",
                invoiceNo, payload.getId(), payload.getTransferAmount());

        if (PaymentCallbackType.SESSION.name().equals(payment.getCallbackType())) {
            JSONObject context = payment.getCallbackContext();
            ioTService.publishPaymentSuccess(
                    context.getStr("barrierId"),
                    context.getLong("sessionId"),
                    context.getStr("plateNumber"));
        } else if (PaymentCallbackType.TICKET.name().equals(payment.getCallbackType())) {
            JSONObject context = payment.getCallbackContext();
            TicketEntity ticket = ticketRepository.findById(context.getLong("ticketId"))
                    .orElseThrow(() -> new ServiceException("Không tìm thấy ticket tương ứng", 404));
            TicketStatus currentStatus = ticket.getStatus();
            if (currentStatus.equals(TicketStatus.DEAD) || currentStatus.equals(TicketStatus.INACTIVE)) {
                ticket.setStatus(TicketStatus.ACTIVE);
            } else if (currentStatus.equals(TicketStatus.ACTIVE)) {
                // phát sinh 2 lần thanh toán cho một ticket
                // fixme: phương hướng tạm thời hiện giờ là cộng dồn
                TicketType ticketType = calculatorService.getPricingType(payment.getAmount());
                if (ticketType == TicketType.MONTH) {
                    ticket.setExpireAt(ticket.getExpireAt().plusMonths(1));
                } else if (ticketType == TicketType.QUARTER) {
                    ticket.setExpireAt(ticket.getExpireAt().plusDays(15));
                }
            }
            ticketRepository.save(ticket);
        } else {
            throw new ServiceException(
                    "Chưa hỗ trợ callback type: " + payment.getCallbackType(), 404);
        }
    }

    // INQUIRY — DB local + fallback SePay nếu PENDING
    public PaymentInquiryResponse inquiryByInvoiceNo(String invoiceNo) {
        PaymentEntity intent = paymentRepository.findByInvoiceNo(invoiceNo).orElse(null);
        if (intent != null && (PaymentCallbackType.SUBSCRIPTION.name().equals(intent.getCallbackType())
                || isParkingFeeIntent(intent))) {
            // Only the verified callback commits money and entitlements. Polling cannot grant a period.
            return PaymentInquiryResponse.builder().invoiceNo(intent.getInvoiceNo()).status(intent.getStatus())
                    .amount(intent.getAmount()).callbackType(intent.getCallbackType())
                    .subscriptionId(intent.getCallbackContext() == null ? null
                            : intent.getCallbackContext().getLong("subscriptionId"))
                    .resultCode(intent.getResultCode()).resultMessage(intent.getResultMessage()).build();
        }
        SePayTransaction sePayTransaction = findSePayTransactionByInvoice(invoiceNo);
        if (sePayTransaction == null) return null;

        log.info("SePayTransaction found: {}",sePayTransaction);

        return PaymentInquiryResponse.builder()
                .invoiceNo(invoiceNo)
                .status(PaymentType.PAID.name())
                .amount(sePayTransaction.getAmountIn())
                .metadata(JSONUtil.toJsonPrettyStr(sePayTransaction))
                .build();
    }

    private boolean isParkingFeeIntent(PaymentEntity payment) {
        JSONObject context = payment.getCallbackContext();
        return PaymentCallbackType.SESSION.name().equals(payment.getCallbackType())
                && context != null && (context.containsKey("calculationId")
                || context.containsKey("parkingFeePaymentId"));
    }

    private void settleParkingFee(PaymentEntity payment, SePayWebhookPayload payload) {
        if (!"SEPAY".equals(payment.getGateway()) || !"VND".equals(payment.getCurrency())) {
            throw new ServiceException("Webhook SePay không khớp cổng thanh toán/tiền tệ", 409);
        }
        // Webhook JSON itself is not trusted payment evidence. Reuse the authenticated v2
        // lookup and require one exact invoice, transaction identity and configured beneficiary.
        SePayTransaction transaction = findVerifiedSubscriptionTransaction(payment.getInvoiceNo(), payload);
        if (transaction == null || transaction.getId() == null || transaction.getId().isBlank()
                || !matchesWebhookIdentity(transaction, payload)
                || !matchesInvoice(transaction, payment.getInvoiceNo())
                || hasContradictoryInvoice(transaction.getCode(), payment.getInvoiceNo())
                || hasContradictoryInvoice(transaction.getTransactionContent(), payment.getInvoiceNo())
                || hasContradictoryInvoice(payload.getCode(), payment.getInvoiceNo())
                || hasContradictoryInvoice(payload.getContent(), payment.getInvoiceNo())
                || transaction.getAmountIn() == null || transaction.getAmountIn().signum() <= 0
                || (transaction.getAmountOut() != null && transaction.getAmountOut().signum() != 0)
                || (transaction.getTransferType() != null && !"in".equalsIgnoreCase(transaction.getTransferType()))
                || payload.getTransferAmount() == null
                || transaction.getAmountIn().compareTo(payload.getTransferAmount()) != 0
                || sepayConfig.getBankAccount() == null
                || !sepayConfig.getBankAccount().equals(transaction.getAccountNumber())) {
            throw new ServiceException("Không xác minh được giao dịch SePay cho phí phiên", 409);
        }
        long amount;
        try {
            amount = transaction.getAmountIn().longValueExact();
        } catch (ArithmeticException ex) {
            throw new ServiceException("Giao dịch SePay không có số tiền VND nguyên hợp lệ", 409);
        }
        ParkingPaymentService service = parkingPayments == null ? null : parkingPayments.getIfAvailable();
        if (service == null) throw new ServiceException("Chưa cấu hình xử lý thu phí phiên", 503);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("verification", "AUTHENTICATED_TRANSACTION_INQUIRY");
        metadata.put("webhookTransactionId", payload.getId());
        if (transaction.getReferenceNumber() != null) metadata.put("bankReference", transaction.getReferenceNumber());
        if (transaction.getTransactionDate() != null) metadata.put("gatewayPaidAt", transaction.getTransactionDate());
        service.confirmVerifiedPayment(new VerifiedParkingPayment(payment.getId(), "SEPAY",
                transaction.getId(), payment.getInvoiceNo(), amount, "VND",
                verifiedOffsetTime(transaction.getTransactionDate()), Instant.now(), PaymentType.PAID.name(), metadata));
    }

    private boolean hasContradictoryInvoice(String content, String invoiceNo) {
        if (content == null) return false;
        Matcher matcher = Pattern.compile(Pattern.quote(sepayConfig.getInvoicePrefix()) + "\\d+").matcher(content);
        while (matcher.find()) if (!invoiceNo.equals(matcher.group())) return true;
        return false;
    }

    public SePayTransaction findSePayTransactionByInvoice(String invoiceNo) {
        try {
            String url = UriComponentsBuilder
                    .fromUriString(sepayConfig.getTransactionsListUrl())
                    .queryParam("q", invoiceNo)
                    .build()
                    .toUri()
                    .toString();
            SePayTransactionListResponse resp = restClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + sepayConfig.getApiToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(SePayTransactionListResponse.class);

            if (resp == null || resp.getData() == null || resp.getData().isEmpty()) return null;
            for (int i = 0; i < resp.getData().size(); i++) {
                SePayTransaction transaction = resp.getData().get(i);
                if (transaction != null && transaction.getCode() != null
                        && transaction.getCode().trim().equalsIgnoreCase(invoiceNo.trim())) {
                    return transaction;
                }
            }
            return null;
        } catch (Exception e) {
            log.error("SePay inquiry error invoice={}: {}", invoiceNo, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Separate subscription verification query. SePay webhook IDs are numeric, while API v2
     * IDs are UUIDs; bridge those namespaces using the authenticated bank reference + account.
     */
    public SePayTransaction findVerifiedSubscriptionTransaction(String invoiceNo, SePayWebhookPayload payload) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder
                    .fromUriString(sepayConfig.getSubscriptionTransactionsListUrl())
                    .queryParam("q", invoiceNo)
                    .queryParam("timestamp_format", "iso8601")
                    .queryParam("per_page", 100);
            if (payload.getReferenceCode() != null && !payload.getReferenceCode().isBlank()) {
                uri.queryParam("reference_number", payload.getReferenceCode());
            }
            SePayTransactionListResponse response = restClient.get().uri(uri.build().toUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + sepayConfig.getApiToken())
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(SePayTransactionListResponse.class);
            if (response == null || response.getData() == null
                    || !("success".equalsIgnoreCase(response.getStatus()) || "200".equals(response.getStatus()))) return null;
            SePayTransaction match = null;
            for (SePayTransaction transaction : response.getData()) {
                if (transaction == null || !matchesInvoice(transaction, invoiceNo)
                        || !matchesWebhookIdentity(transaction, payload)) continue;
                // Never guess which receipt to use if the provider returns ambiguous matches.
                if (match != null) return null;
                match = transaction;
            }
            return match;
        } catch (Exception ex) {
            log.warn("SePay subscription inquiry failed for invoice={}", invoiceNo);
            return null;
        }
    }

    private boolean matchesInvoice(SePayTransaction transaction, String invoiceNo) {
        return invoiceNo.equals(transaction.getCode())
                || invoiceNo.equals(extractInvoiceNo(transaction.getTransactionContent()));
    }

    private boolean matchesWebhookIdentity(SePayTransaction transaction, SePayWebhookPayload payload) {
        if (payload.getId() != null && payload.getId().equals(transaction.getId())) return true;
        // Official API migration guidance maps v1 numeric IDs to v2 UUIDs by business fields.
        return payload.getReferenceCode() != null && !payload.getReferenceCode().isBlank()
                && payload.getReferenceCode().equals(transaction.getReferenceNumber())
                && payload.getAccountNumber() != null
                && payload.getAccountNumber().equals(transaction.getAccountNumber())
                && sepayConfig.getBankAccount() != null
                && sepayConfig.getBankAccount().equals(transaction.getAccountNumber())
                && payload.getTransferAmount() != null && transaction.getAmountIn() != null
                && payload.getTransferAmount().compareTo(transaction.getAmountIn()) == 0;
    }

    private void settleSubscription(PaymentEntity payment, SePayWebhookPayload payload) {
        SePayTransaction transaction = findVerifiedSubscriptionTransaction(payment.getInvoiceNo(), payload);
        if (transaction == null || transaction.getId() == null || transaction.getId().isBlank()
                || !matchesWebhookIdentity(transaction, payload)
                || !matchesInvoice(transaction, payment.getInvoiceNo())
                || transaction.getAmountIn() == null || transaction.getAmountIn().signum() <= 0
                || (transaction.getAmountOut() != null && transaction.getAmountOut().signum() != 0)
                || (transaction.getTransferType() != null && !"in".equalsIgnoreCase(transaction.getTransferType()))
                || payload.getTransferAmount() == null
                || transaction.getAmountIn().compareTo(payload.getTransferAmount()) != 0
                || sepayConfig.getBankAccount() == null
                || !sepayConfig.getBankAccount().equals(transaction.getAccountNumber())) {
            throw new ServiceException("Không xác minh được giao dịch SePay cho kỳ thuê bao", 409);
        }
        Long subscriptionId = payment.getCallbackContext() == null ? null
                : payment.getCallbackContext().getLong("subscriptionId");
        if (subscriptionId == null) throw new ServiceException("Payment thiếu subscriptionId", 409);
        long amount;
        try {
            amount = transaction.getAmountIn().longValueExact();
        } catch (ArithmeticException ex) {
            throw new ServiceException("Giao dịch SePay không có số tiền VND nguyên hợp lệ", 409);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("verification", "AUTHENTICATED_TRANSACTION_INQUIRY");
        metadata.put("webhookTransactionId", payload.getId());
        if (transaction.getReferenceNumber() != null) metadata.put("bankReference", transaction.getReferenceNumber());
        if (transaction.getTransactionDate() != null) metadata.put("gatewayPaidAt", transaction.getTransactionDate());
        vehicleSubscriptionService.confirmVerifiedPayment(new VerifiedSubscriptionPayment(
                subscriptionId, payment.getId(), "SEPAY", transaction.getId(), payment.getInvoiceNo(),
                amount, "VND", verifiedOffsetTime(transaction.getTransactionDate()), null, metadata));
    }

    private Instant verifiedOffsetTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            // SePay v2 timestamp_format=iso8601 includes the actual UTC offset.
            return OffsetDateTime.parse(value).toInstant();
        } catch (java.time.format.DateTimeParseException ex) {
            // No timezone inference and no server-time fallback for entitlement deadlines.
            return null;
        }
    }

    private String extractInvoiceNo(String content) {
        if (content == null) return null;
        String prefix = sepayConfig.getInvoicePrefix();
        Pattern pattern = Pattern.compile("(" + Pattern.quote(prefix) + "\\d+)");
        Matcher m = pattern.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private LocalDateTime parseSePayDate(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(s, SEPAY_DATE_FMT);
        } catch (Exception e) {
            log.warn("SePay date parse fail: {}", s);
            return LocalDateTime.now();
        }
    }

    private String generateInvoiceNo() {
        // Decimal UUID keeps the existing prefix+digits parser and avoids same-millisecond collisions.
        return sepayConfig.getInvoicePrefix() + new java.math.BigInteger(
                java.util.UUID.randomUUID().toString().replace("-", ""), 16).toString();
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[!@#$%&*<=>?^'|\"\\r\\n]", "").trim();
    }
}
