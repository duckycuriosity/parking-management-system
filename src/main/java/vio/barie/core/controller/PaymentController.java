package vio.barie.core.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.util.JSONPObject;

@RestController("/payment")
public class PaymentController {

    @PostMapping("/callBackUrl")
    public void callBack(@RequestBody String txt){
        System.out.println(txt);
    }
}
