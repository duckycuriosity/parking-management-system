package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.payment.MegaPayResponse;
import vio.barie.core.dto.payment.RefundRequest;
import vio.barie.core.service.PaymentService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // redirect ve UI
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam Map<String, String> params) {
        log.info("MegaPay callback: invoiceNo={}, resultCd={}",
                params.get("invoiceNo"), params.get("resultCd"));
        paymentService.handleCallback(params);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/ipn")
    public ResponseEntity<String> ipn(@RequestBody MegaPayResponse params) {
        log.info("MegaPay IPN: invoiceNo, resultCd={}",
                params.getInvoiceNo(), params.getResultCd());
        paymentService.handleIPN(params);
        return ResponseEntity.ok("OK");
    }

    @GetMapping("/inquiry/{invoiceNo}")
    public ResponseEntity<MegaPayResponse> inquiry(@PathVariable String invoiceNo) {
        MegaPayResponse resp = paymentService.inquiryByInvoiceNo(invoiceNo);
        if (resp == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(resp);
    }
}
