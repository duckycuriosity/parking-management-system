package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.dto.payment.*;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.repository.PaymentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Calendar;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    private final MegaPayConfig config;

    private final RestClient restClient = RestClient.create();

    public CreatePaymentResponse createPaymentLink(CreatePaymentRequest req) {
        try {
            JSONObject body = buildRequestBody(req);
            String response = restClient.post()
                    .uri(config.getEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            return JSONUtil.toBean(response,CreatePaymentResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Create payment failed", e);
        }
    }

    private JSONObject buildRequestBody(CreatePaymentRequest req) {
        JSONObject body = new JSONObject();
        body.set("merId", config.getMerId());
        body.set("currency", "VND");
        body.set("amount", req.getAmount());
        body.set("goodsNm", cleanSpecialChar(req.getGoodsName()));
        body.set("payType", config.getPayType());
        body.set("callBackUrl", config.getCallbackUrl());
        body.set("notiUrl", config.getNotifyUrl());
        body.set("reqDomain", config.getReqDomain());
        body.set("descriptions", cleanSpecialChar(req.getDescription()));
        body.set("userLanguage", config.getUserLanguage());
        body.set("windowType", config.getWindowType());

        String timeStamp = String.valueOf(System.currentTimeMillis());
        String invoiceNo = generateInvoiceNo(req.getSession());

        String linkExptime = generateExpireTime();

        body.set("timeStamp", timeStamp);
        body.set("invoiceNo", invoiceNo);
        body.set("linkExptime", linkExptime);

        // TOKEN
        String merchantToken = generateSHA256(

                timeStamp
                    + invoiceNo
                    + body.get("merId")
                    + body.get("amount")
                    + linkExptime
                    + config.getEncodeKey()
        );

        body.put("merchantToken", merchantToken);

        // HASH
        String hash = generateSHA256(
                invoiceNo
                + body.get("amount")
                + config.getEncodeKey());
        body.put("hash", hash);

        return body;
    }

    private String cleanSpecialChar(String input) {
        return input.replaceAll("[!@#$%&*<=>?^'|\"\\r\\n]", "");
    }

    // Encode sessionId vào invoiceNo để callback có thể truy ngược ra session
    private String generateInvoiceNo(ParkingSessionEntity session) {
        return "viotech_" + session.getId() + "_" + System.currentTimeMillis();
    }

    // QR hết hạn sau 15p
    private String generateExpireTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 15);
        return sdf.format(cal.getTime());
    }

    // Hàm phụ để sinh SHA256 cho gọn
    private String generateSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }



    @Transactional
    public void completePayment(MegaPayResponse response, String method) {
        if (verify(response)) {
            PaymentEntity payment = paymentRepository.findByInvoiceNo(response.getInvoiceNo())
                    .orElseThrow(() -> new ServiceException("Không tìm thấy payment theo invoiceNo", 404));

            // tránh trùng lặp
            if ("PAID".equals(payment.getStatus())) {
                log.warn("Payment {} đã trả đủ ", response.getInvoiceNo());
                return;
            }

            // check amount, mặc định là khóa cứng
            int amount = Integer.parseInt(response.getAmount());
            if (amount != payment.getAmount()) {
                log.error("Số tiền không khớp invoice={} db={} resp={}",
                        response.getInvoiceNo(), payment.getAmount(), amount);
                payment.setStatus("FRAUD_SUSPECTED");
                paymentRepository.save(payment);
                throw new ServiceException("Số tiền không khớp", 400);
            }

            payment.setStatus("PAID");
            payment.setPaidAt(LocalDateTime.now());
            if (method != null) payment.setPaymentMethod(method);
            paymentRepository.save(payment);
        }
    }

    // check token hợp lệ
    public boolean verify(MegaPayResponse response) {
        String expected = generateSHA256(
                response.getResultCd() + response.getTimeStamp() + response.getMerTrxId() +
                        response.getTrxId() + response.getMerId() + response.getAmount() + config.getEncodeKey()
        );
        boolean isValid = expected.equalsIgnoreCase(response.getMerchantToken());
        if (!isValid) {
            log.error("Token của invoiceNo:[ {} ] không hợp lệ !", response.getInvoiceNo());
            log.error("Expected: ", expected);
            log.error("Input: ", response.getMerchantToken());
        }
        return isValid;
    }

    // truy vấn lại dữ liệu thanh toán cũ dựa trên invoiceNo
    public MegaPayResponse inquiryByInvoiceNo(String invoiceNo) {
        String timeStamp = String.valueOf(System.currentTimeMillis());
        String token = generateSHA256(timeStamp + invoiceNo + config.getMerId() + config.getEncodeKey());

        MegaPayInquiryRequest inquiryRequest = MegaPayInquiryRequest.builder()
                .invoiceNo(invoiceNo)
                .merId(config.getMerId())
                .merchantToken(token)
                .timeStamp(timeStamp)
                .build();

        MegaPayInquiryResponse resp = restClient.post()
                .uri(config.getInquiryEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSONUtil.toJsonStr(inquiryRequest))
                .retrieve()
                .body(MegaPayInquiryResponse.class);

        if (resp == null ) {
            log.error("Truy vấn hóa đơn : {} thất bại", invoiceNo);
            return null;
        }
        return resp.getData();
    }
    
}
