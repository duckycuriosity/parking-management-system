package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.ParkingSessionEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Calendar;

@Service
@RequiredArgsConstructor
public class PaymentService {

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
        body.set("goodsNm", req.getGoodsName());
        body.set("payType", config.getPayType());
        body.set("callBackUrl", config.getCallbackUrl());
        body.set("notiUrl", config.getNotifyUrl());
        body.set("reqDomain", config.getReqDomain());
        body.set("descriptions", req.getDescription());
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

    // Todo: sửa cái này sau , hiện giờ chỉ dùng để kiểm thử
    private String generateInvoiceNo(ParkingSessionEntity session) {
        return "vio_tech_" + System.currentTimeMillis();
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
    
}
