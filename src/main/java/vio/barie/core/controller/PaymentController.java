package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.util.JSONPObject;
import vio.barie.core.dto.payment.MegaPayResponse;

// controller để hứng callback từ megapay, tạo đơn thanh toán
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    @PostMapping("/callBackUrl")
    public void callBack(@RequestBody MegaPayResponse resp){

    }

    @PostMapping("/ipn")
    public void ipn(@RequestBody MegaPayResponse resp){

    }
}
