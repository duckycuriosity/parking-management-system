package vio.barie.core;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Calendar;
public class CoreApplicationTests {

	@Test
	public void testAPI() throws JSONException {
		// 1. Tạo body JSON
		JSONObject body = new JSONObject();
		// Dùng put thay vì parse String để dễ dàng kiểm soát kiểu dữ liệu
		body.put("merId", "EPAY000001");
		body.put("currency", "VND");

		// QUAN TRỌNG: Đảm bảo amount là Number (không có ngoặc kép trong JSON)
		// JSONObject sẽ tự nhận diện số nếu bạn put trực tiếp Long/Double
		body.put("amount", 50000);

		body.put("goodsNm", "Gui xe thang 4");
		body.put("payType", "VA"); // Dùng 'NO' như code mẫu để test đơn giản trước
		body.put("callBackUrl", "http://localhost:3000/payment/callback");
		body.put("notiUrl", "http://localhost:3000/payment/ipn");
		body.put("reqDomain", "http://localhost:3000");

		// SỬA LỖI: descriptions (số nhiều) thay vì description
		body.put("descriptions", "Thanh toan phi gui xe");

		body.put("userLanguage", "VN");

		// Các trường còn thiếu
		body.put("windowType", "1");
		body.put("userId", "viotech");

		// Tạo thời gian và InvoiceNo
		String timeStamp = String.valueOf(System.currentTimeMillis());
		String invoiceNo = "vio_test_" + timeStamp;

		// Tính thời gian hết hạn (linkExptime)
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.DATE, 1); // Hết hạn sau 1 ngày
		String linkExptime = sdf.format(cal.getTime());

		body.put("timeStamp", timeStamp);
		body.put("invoiceNo", invoiceNo);
		body.put("linkExptime", linkExptime);

		String encodeKey = "rf8whwaejNhJiQG2bsFubSzccfRc/iRYyGUn6SPmT6y/L7A2XABbu9y4GvCoSTOTpvJykFi6b1G0crU8et2O0Q==";

		// 2. Gen merchantToken (Giống code mẫu)
		String rawDataToken = timeStamp
				+ invoiceNo
				+ body.getString("merId")
				+ body.getString("amount")
				+ linkExptime
				+ encodeKey;

		String merchantToken = generateSHA256(rawDataToken);
		body.put("merchantToken", merchantToken);

		// 3. Gen hash (THIẾU trong code cũ - Bắt buộc có)
		String rawDataHash = invoiceNo + body.getString("amount") + encodeKey;
		String hash = generateSHA256(rawDataHash);
		body.put("hash", hash); // THÊM TRƯỜNG NÀY

		System.out.println("Request body: " + body.toString());

		// 4. Gửi request
		RestClient restClient = RestClient.create();
		String response = restClient.post()
				.uri("https://sandbox.megapay.vn/pg_was/createlink.do")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString()) // Gửi chuỗi JSON
				.retrieve()
				.body(String.class);

		System.out.println("Response: " + response);
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