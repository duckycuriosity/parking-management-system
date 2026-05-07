package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.payment.MegaPayResponse;
import vio.barie.core.service.ParkingService;
import vio.barie.core.service.PaymentService;

// controller để hứng callback từ megapay, tạo đơn thanh toán
@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // todo: đây là megapay callback cho UI , tạm thời để đây, sau này có UI thì chỏ về UI
    @PostMapping("/callback")
    public void callback(@RequestBody MegaPayResponse response) {
        log.info("MegaPay callback invoiceNo={} resultCd={}", response.getInvoiceNo(), response.getResultCd());

    }

    // đây là megapay callback cho server
    @PostMapping("/ipn")
    public void ipn(@RequestBody MegaPayResponse response) {
        log.info("MegaPay IPN invoiceNo={} resultCd={}", response.getInvoiceNo(), response.getResultCd());
        if (response.isSuccess()) {
            paymentService.completePayment(response, "QR");
        } else {
            // todo: xử lý nếu thanh toán lỗi
        }
    }
}
