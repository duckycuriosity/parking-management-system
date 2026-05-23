package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.dto.payment.*;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.utils.MegaPaySignatureUtil;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import static cn.hutool.core.convert.Convert.hexToBytes;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final MegaPayConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();
    private final ParkingMessageService ioTService;

    public PaymentEntity createSessionPayment(
            String invoiceNo,
            BigDecimal amount,
            String payType,
            String description,
            Long sessionId,
            String barrierId,
            String plateNumber,
            Integer aiIdentityCustomerId,
            Long laneId,
            LocalDateTime exitTime
    ) {
        PaymentEntity payment = new PaymentEntity();

        payment.setInvoiceNo(invoiceNo);
        payment.setAmount(amount);
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setPayType(payType);
        payment.setDescription(sanitize(description));
        payment.setStatus("PENDING");
        payment.setCallbackType(PaymentCallbackType.SESSION.name());

        JSONObject context = new JSONObject();
        context.set("sessionId", sessionId);
        context.set("barrierId", barrierId);
        context.set("plateNumber", plateNumber);
        context.set("aiIdentityCustomerId", aiIdentityCustomerId);
        context.set("laneId", laneId);
        context.set("exitTime", exitTime);

        payment.setCallbackContext(context);

        return paymentRepository.save(payment);
    }

    public PaymentEntity createTicketPayment(
            String invoiceNo,
            BigDecimal amount,
            String payType,
            String description,
            Long ticketId
//            Long customerId
    ) {
        PaymentEntity payment = new PaymentEntity();

        payment.setInvoiceNo(invoiceNo);
        payment.setAmount(amount);
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setPayType(payType);
        payment.setDescription(sanitize(description));
        payment.setStatus("PENDING");
        payment.setCallbackType(PaymentCallbackType.TICKET.name());

        JSONObject context = new JSONObject();
        context.set("ticketId", ticketId);
//        context.set("customerId", customerId);
        payment.setCallbackContext(context);

        return paymentRepository.save(payment);
    }

    // TẠO PAYMENT LINK — lưu PaymentEntity với trạng thái PENDING
    public CreatePaymentResponse createPaymentLink(CreatePaymentRequest req) {
        String timeStamp = String.valueOf(System.currentTimeMillis());
        String amountStr = String.valueOf(req.getAmount());
        String invoiceNo = generateInvoiceNo(req);

        String linkExptime = generateExpireTime();
        String payType = req.getPayType() != null ? req.getPayType() : config.getPayType();

        String merchantToken = MegaPaySignatureUtil.generatePaymentToken(
                timeStamp, invoiceNo, config.getMerId(), amountStr,linkExptime, config.getEncodeKey());

        String hashData = invoiceNo + amountStr + config.getEncodeKey();
        String hash = MegaPaySignatureUtil.sha256(hashData);

        Map<String, Object> params = new HashMap<>();
        params.put("merId", config.getMerId());
        params.put("currency", "VND");
        params.put("amount", amountStr);
        params.put("invoiceNo", invoiceNo);
        params.put("goodsNm", sanitize(req.getGoodsName()));
        params.put("payType", payType);
        params.put("callBackUrl", config.getCallbackUrl());
        params.put("notiUrl", config.getNotifyUrl());
        params.put("reqDomain", config.getReqDomain());
        params.put("descriptions", sanitize(req.getDescription()));
        params.put("merchantToken", merchantToken);
        params.put("userLanguage", config.getUserLanguage() != null ? config.getUserLanguage() : "VN");
        params.put("timeStamp", timeStamp);
        params.put("linkExptime", linkExptime);
        params.put("hash", hash);
        if (config.getWindowColor() != null) params.put("windowColor", config.getWindowColor());

        try {
            CreatePaymentResponse response = createPaymentLink(params);

            if (!"00_000".equals(response.getResultCd())) {
                throw new ServiceException("Tạo link thanh toán thất bại: " + response.getResultMsg(), 400);
            }
            return response;

        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("createPaymentLink error: {}", e.getMessage(), e);
            throw new ServiceException("Tạo link thanh toán lỗi hệ thống", e);
        }
    }

    // CALLBACK
    @Transactional
    public void handleCallback(Map<String, String> params) {
    }

    // IPN
    @Transactional
    public void handleIPN(MegaPayResponse response) {

        boolean tokenValid = MegaPaySignatureUtil.verifyPaymentCallback(
                response.getResultCd(),
                response.getTimeStamp(),
                response.getMerTrxId(),
                response.getTrxId(),
                config.getMerId(),
                response.getAmount(),
                config.getEncodeKey(),
                response.getMerchantToken());
        if (!tokenValid) {
            log.warn("IPN token không hợp lệ, invoiceNo={}", response.getInvoiceNo());
            return;
        }

        PaymentEntity payment = paymentRepository.findByInvoiceNo(response.getInvoiceNo())
                .orElseThrow(() -> new ServiceException(
                        "IPN: không tìm thấy payment với invoiceNo={}" + response.getInvoiceNo(),
                        404));

        // xac nhan thanh toan da hoan thanh
        if (payment.getCallbackType().equals(PaymentCallbackType.SESSION.name())){
            // thong bao den hardware qua mqtt de ui chuyen man hinh thanh: Dang xu ly
            JSONObject context = payment.getCallbackContext();
            ioTService.publishPaymentConfirmed(context.getStr("barrierId"),
                    context.getLong("sessionId"),
                    new BigDecimal(response.getAmount()),
                    response.getInvoiceNo());
        }
        else if (payment.getCallbackType().equals(PaymentCallbackType.TICKET.name())) {
            // todo: thong bao den ui qua ws,.. de ui chuyen man hinh thanh: Dang xu ly
        } else {
            throw new ServiceException("Chưa hỗ trợ callback type: "+payment.getCallbackType(),404);
        }

        // Tránh xử lý 2 lần
        if ("PAID".equals(payment.getStatus())) {
            log.info("IPN: payment voi invoiceNo {} đã PAID, bỏ qua", response.getInvoiceNo());
            return;
        }

        if ("00_000".equals(response.getResultCd())) {
            payment.setStatus("PAID");
            payment.setPaidAt(LocalDateTime.now());
            // double check
            inquiryByInvoiceNo(response.getInvoiceNo());
        } else if ("99".equals(response.getResultCd())) {
            payment.setStatus("PENDING");
        } else {
            payment.setStatus("FAILED");
        }

        payment.setResultCode(response.getResultCd());
        payment.setResultMessage(response.getResultMsg());

        if (response.getPayType() != null) payment.setPayType(response.getPayType());

        paymentRepository.save(payment);
        log.info("IPN processed: invoiceNo={}, status={}", response.getInvoiceNo(), payment.getStatus());
        // payment callback
        if (payment.getCallbackType().equals(PaymentCallbackType.SESSION.name())){
            JSONObject context = payment.getCallbackContext();
            // thong bao giao dich thanh cong de mo cong
            ioTService.publishPaymentSuccess(context.getStr("barrierId"),
                    context.getLong("sessionId"),
                    context.getStr("plateNumber"));
        } else if (payment.getCallbackType().equals(PaymentCallbackType.TICKET.name())) {
            // ticket status dead -> active
            JSONObject context = payment.getCallbackContext();
            TicketEntity ticket = ticketRepository.findById(context.getLong("ticketId")).orElseThrow(
                    () -> new ServiceException("Không tìm ticket tương ", 404)
            );
            ticket.setStatus(TicketStatus.ACTIVE.name());
            ticketRepository.save(ticket);
        } else {
            throw new ServiceException("Chưa hỗ trợ callback type: "+payment.getCallbackType(),404);
        }
    }

    // TRA CỨU GIAO DỊCH
    public MegaPayResponse inquiryByInvoiceNo(String invoiceNo) {
        String timeStamp = String.valueOf(System.currentTimeMillis());
        String token = MegaPaySignatureUtil.generateInquiryToken(
                timeStamp, invoiceNo, config.getMerId(), config.getEncodeKey());

        Map<String, String> params = new HashMap<>();
        params.put("merId", config.getMerId());
        params.put("invoiceNo", invoiceNo);
        params.put("merchantToken", token);
        params.put("timeStamp", timeStamp);

        try {
            MegaPayResponse resp = inquiryByInvoiceNo(params);
            if (resp == null) return null;

            paymentRepository.findByInvoiceNo(invoiceNo).ifPresent(tx -> {
                String rc = resp.getResultCd();
                if ("00_000".equals(rc)) {
                    tx.setStatus("PAID");
                    tx.setPaidAt(LocalDateTime.now());
                } else if ("99".equals(rc)) {
                    tx.setStatus("PENDING");
                } else {
                    tx.setStatus("FAILED");
                }
                tx.setResultCode(rc);
                tx.setResultMessage(resp.getResultMsg());
                paymentRepository.save(tx);
            });

            return resp;
        } catch (Exception e) {
            log.error("Inquiry error invoiceNo={}: {}", invoiceNo, e.getMessage(), e);
            return null;
        }
    }

    // DECODE 3DES ECB
    public String decodePaymentLink(String hexEncoded) {
        try {
            byte[] keyBytes = config.getDecryptKey().getBytes(StandardCharsets.UTF_8);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "DESede");
            Cipher cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(hexToBytes(hexEncoded));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decode paymentLink thất bại", e);
        }
    }

    private String generateInvoiceNo(CreatePaymentRequest req) {
        return "viotech_" + System.currentTimeMillis();
    }

    private String generateExpireTime() {
        // QR hết hạn sau 15 phút
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 15);
        return sdf.format(cal.getTime());
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[!@#$%&*<=>?^'|\"\\r\\n]", "").trim();
    }

    public MegaPayResponse inquiryByInvoiceNo(Map<String, String> params) {
        log.debug("MegaPay inquiry (invoiceNo) params: {}", params);
        MultiValueMap<String, String> form = toForm(params);
        return restClient.post()
                .uri(config.getInquiryByInvoiceNoUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(MegaPayResponse.class);
    }


    public CreatePaymentResponse createPaymentLink(Map<String, Object> params) {
        log.debug("MegaPay createLink params: {}", params);
        String respBody = restClient.post()
                .uri(config.getCreateLinkUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(params)
                .retrieve()
                .body(String.class);
        log.debug("MegaPay createLink response: {}",respBody);
        return JSONUtil.toBean(respBody,CreatePaymentResponse.class);
    }

    private MultiValueMap<String, String> toForm(Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        return form;
    }
    
    
}
