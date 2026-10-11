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
import vio.barie.core.common.PaymentType;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.*;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.CalculatorService;
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
    private final CalculatorService calculatorService;

    private static final DateTimeFormatter SEPAY_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        String invoiceNo = extractInvoiceNo(payload.getCode());
        if (invoiceNo == null) {
            log.warn("SePay webhook: không tìm thấy invoiceNo trong content='{}', payload={}",
                    payload.getContent(), payload);
            return;
        }

        // (4) Tìm payment + so khớp số tiền
        PaymentEntity payment = paymentRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new ServiceException(
                        "SePay webhook: không tìm thấy payment invoiceNo=" + invoiceNo, 404));

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
                if (resp.getData().get(i).getCode().toLowerCase().trim().equals(invoiceNo.toLowerCase().trim())){
                    return resp.getData().get(i);
                }
            }
            return null;
        } catch (Exception e) {
            log.error("SePay inquiry error invoice={}: {}", invoiceNo, e.getMessage(), e);
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
        return sepayConfig.getInvoicePrefix() + System.currentTimeMillis();
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[!@#$%&*<=>?^'|\"\\r\\n]", "").trim();
    }
}
