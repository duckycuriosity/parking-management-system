package vio.barie.core.service.payment.sepay;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.*;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentGateway;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
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

    private static final DateTimeFormatter SEPAY_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final String STATUS_PAID    = "PAID";
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_FAILED  = "FAILED";
    static final String STATUS_FRAUD   = "FRAUD_SUSPECTED";

    // TẠO QR
    public CreatePaymentResponse createPaymentLink(CreatePaymentRequest req) {
        if (req.getAmount() == null || req.getAmount().signum() <= 0) {
            throw new ServiceException("Số tiền phải > 0", 400);
        }
        if (sepayConfig.getBankAccount() == null || sepayConfig.getBankAccount().isBlank()) {
            throw new ServiceException("Chưa cấu hình sepay.bank-account", 500);
        }

        String invoiceNo = generateInvoiceNo();
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
        resp.setQrCode(qrUrl);
        resp.setPaymentLink(qrUrl);
        resp.setResultCd("00_000");
        resp.setResultMsg("OK");
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
        if (paymentRepository.existsBySepayTxId(payload.getId())) {
            log.info("SePay webhook: tx id={} đã xử lý, bỏ qua", payload.getId());
            return;
        }

        // (2) Chỉ xử lý tiền vào
        if (!"in".equalsIgnoreCase(payload.getTransferType())) {
            log.debug("SePay webhook: bỏ qua transferType={}", payload.getTransferType());
            return;
        }

        // (3) Parse invoiceNo
        String invoiceNo = extractInvoiceNo(payload.getContent());
        if (invoiceNo == null) {
            log.warn("SePay webhook: không tìm thấy invoiceNo trong content='{}', txId={}",
                    payload.getContent(), payload.getId());
            return;
        }

        // (4) Tìm payment + so khớp số tiền
        PaymentEntity payment = paymentRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ServiceException(
                        "SePay webhook: không tìm thấy payment invoiceNo=" + invoiceNo, 404));

        if (payment.getAmount() != null && payload.getTransferAmount() != null
                && payment.getAmount().compareTo(payload.getTransferAmount()) != 0) {
            log.warn("SePay webhook: lệch số tiền invoice={} expected={} actual={}",
                    invoiceNo, payment.getAmount(), payload.getTransferAmount());
            payment.setStatus(STATUS_FRAUD);
            payment.setResultMessage("Amount mismatch: expected="
                    + payment.getAmount() + " actual=" + payload.getTransferAmount());
            payment.setSepayTxId(payload.getId());
            payment.setGateway(payload.getGateway());
            payment.setReferenceCode(payload.getReferenceCode());
            paymentRepository.save(payment);
            return;
        }

        if (STATUS_PAID.equals(payment.getStatus())) {
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

        payment.setStatus(STATUS_PAID);
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
            ticket.setStatus(TicketStatus.ACTIVE.name());
            ticketRepository.save(ticket);
        } else {
            throw new ServiceException(
                    "Chưa hỗ trợ callback type: " + payment.getCallbackType(), 404);
        }
    }

    // INQUIRY — DB local + fallback SePay nếu PENDING
    public PaymentInquiryResponse inquiryByInvoiceNo(String invoiceNo) {
        PaymentEntity local = paymentRepository.findByInvoiceNo(invoiceNo).orElse(null);
        if (local == null) return null;

        // Nếu còn PENDING -> thử gọi SePay xem có giao dịch khớp không (webhook miss?)
        if (STATUS_PENDING.equals(local.getStatus())) {
            findSePayTransactionByInvoice(invoiceNo).ifPresent(tx -> {
                log.info("Inquiry: phát hiện invoice {} đã có giao dịch SePay, replay webhook handler",
                        invoiceNo);
                try {
                    handleCallback(tx.toString());
                } catch (Exception e) {
                    log.error("Inquiry replay error: {}", e.getMessage(), e);
                }
            });
            local = paymentRepository.findByInvoiceNo(invoiceNo).orElse(local);
        }

        return PaymentInquiryResponse.builder()
                .invoiceNo(local.getInvoiceNo())
                .status(local.getStatus())
                .amount(local.getAmount())
                .metadata(local.toString())
                .build();
    }

    /** Tìm 1 giao dịch SePay khớp invoiceNo */
    public Optional<SePayTransaction> findSePayTransactionByInvoice(String invoiceNo) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder
                    .fromUriString(sepayConfig.getTransactionsListUrl())
                    .queryParam("q", invoiceNo)
                    .queryParam("limit", 1);
            SePayTransactionListResponse resp = sepayGet(uri.build().toUri().toString(),
                    SePayTransactionListResponse.class);
            if (resp == null || resp.getTransactions() == null || resp.getTransactions().isEmpty()) return Optional.empty();
            return Optional.of(resp.getTransactions().getFirst());
        } catch (Exception e) {
            log.error("SePay inquiry error invoice={}: {}", invoiceNo, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** GET kèm Bearer token. */
    public <T> T sepayGet(String url, Class<T> respType) {
        if (sepayConfig.getApiToken() == null || sepayConfig.getApiToken().isBlank()) {
            throw new ServiceException("Chưa cấu hình sepay.api-token", 500);
        }
        return restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sepayConfig.getApiToken())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(respType);
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
        return sepayConfig.getInvoicePrefix() + System.currentTimeMillis();
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[!@#$%&*<=>?^'|\"\\r\\n]", "").trim();
    }
}
