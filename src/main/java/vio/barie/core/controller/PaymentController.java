package vio.barie.core.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.config.payment.SePayConfig;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.payment.MegaPayResponse;
import vio.barie.core.dto.payment.PaymentInquiryResponse;
import vio.barie.core.dto.payment.PaymentListItemResponse;
import vio.barie.core.dto.payment.RefundRequest;
import vio.barie.core.dto.payment.RevenueReportResponse;
import vio.barie.core.dto.payment.RevenueSummaryResponse;
import vio.barie.core.service.PaymentService;

import java.time.LocalDate;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final SePayConfig sepayConfig;

    // ipn cho megapay
    @PostMapping("/ipn")
    public ResponseEntity<String> ipn(@RequestBody String str) {
        log.info("MegaPay IPN: {}",str);
        paymentService.handleCallback(str);
        return ResponseEntity.ok("OK");
    }

    // Webhook, phải trả HTTP 200 + {"success": true} trong 30s => dung ResponseEntity
    @PostMapping("/webhook/sepay")
    public ResponseEntity<Map<String, Object>> sepayWebhook(
            @RequestBody String str,
            HttpServletRequest request) {

        String auth = request.getHeader("Authorization");
        String expected = "Apikey " + sepayConfig.getWebhookApiKey();
        if (sepayConfig.getWebhookApiKey() == null
                || sepayConfig.getWebhookApiKey().isBlank()
                || !expected.equals(auth)) {
            log.warn("SePay webhook unauthorized: incoming body={}",str);
            return ResponseEntity.status(401).body(Map.of("success", false));
        }

        try {
            paymentService.handleCallback(str);
        } catch (Exception e) {
            log.error("SePay webhook handler error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/inquiry/{invoiceNo}")
    public Result<PaymentInquiryResponse> inquiry(@PathVariable String invoiceNo) {
        PaymentInquiryResponse resp = paymentService.inquiryByInvoiceNo(invoiceNo);
        if (resp == null) return Result.error(404, "Transaction not found");
        return Result.ok(resp);
    }

    @GetMapping
    public Result<Page<PaymentListItemResponse>> getPayments(
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(paymentService.getPaymentList(success, page ,size));
    }

    @GetMapping("/report")
    public Result<RevenueReportResponse> getRevenueReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(paymentService.getRevenueReport(startDate, endDate, page, size));
    }

    @GetMapping("/report/export")
    public ResponseEntity<byte[]> exportRevenueReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        byte[] excel = paymentService.exportRevenueReportToExcel(startDate, endDate);
        String filename = "bao-cao-doanh-thu-" + startDate + "-den-" + endDate + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    @GetMapping("/statistics")
    public Result<RevenueSummaryResponse> getDailyRevenue(
            @RequestParam(required = false) Long parkingLotId,
            @RequestParam(required = false) Long buildingId) {
        return Result.ok(paymentService.getDailyRevenueSummary(parkingLotId, buildingId));
    }
}
