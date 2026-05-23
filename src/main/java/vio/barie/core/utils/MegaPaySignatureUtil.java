package vio.barie.core.utils;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
public class MegaPaySignatureUtil {

    private MegaPaySignatureUtil(){};

    public static String sha256(String input) {
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

    /** Tạo merchantToken khi tạo payment link: SHA256(timeStamp + invoiceNo + merId + amount + linkExpTime + encodeKey) */
    public static String generatePaymentToken(String timeStamp, String invoiceNo,
                                               String merId, String amount,String linkExpTime, String encodeKey) {
        return sha256(timeStamp +invoiceNo+ merId + amount + linkExpTime + encodeKey);
    }

    /** Tạo merchantToken khi có userFee: SHA256(timeStamp + merTrxId + merId + amount + userFee + encodeKey) */
    public static String generatePaymentTokenWithFee(String timeStamp, String merTrxId,
                                                      String merId, String amount,
                                                      String userFee, String encodeKey) {
        return sha256(timeStamp + merTrxId + merId + amount + userFee + encodeKey);
    }

    /** Xác thực token từ callback/IPN: SHA256(resultCd + timeStamp + merTrxId + trxId + merId + amount + encodeKey) */
    public static boolean verifyPaymentCallback(String resultCd, String timeStamp,
                                                 String merTrxId, String trxId,
                                                 String merId, String amount,
                                                 String encodeKey, String receivedToken) {
        String expected = sha256(resultCd + timeStamp + merTrxId + trxId + merId + amount + encodeKey);
        boolean valid = expected.equals(receivedToken);
        if (!valid) {
            log.warn("Token mismatch! Expected: {}, Received: {}", expected, receivedToken);
        }
        return valid;
    }

    /** Xác thực token khi có userFee > 0 */
    public static boolean verifyPaymentCallbackWithFee(String resultCd, String timeStamp,
                                                        String merTrxId, String trxId,
                                                        String merId, String amount,
                                                        String userFee, String encodeKey,
                                                        String receivedToken) {
        String expected = sha256(resultCd + timeStamp + merTrxId + trxId + merId + amount + userFee + encodeKey);
        return expected.equals(receivedToken);
    }

    /** Token cho Inquiry: SHA256(timeStamp + merTrxId + merId + encodeKey) */
    public static String generateInquiryToken(String timeStamp, String merTrxId,
                                               String merId, String encodeKey) {
        return sha256(timeStamp + merTrxId + merId + encodeKey);
    }

    /** Token cho Refund: SHA256(timeStamp + merTrxId + trxId + merId + amount + encodeKey) */
    public static String generateRefundToken(String timeStamp, String merTrxId,
                                              String trxId, String merId,
                                              String amount, String encodeKey) {
        return sha256(timeStamp + merTrxId + trxId + merId + amount + encodeKey);
    }
}
