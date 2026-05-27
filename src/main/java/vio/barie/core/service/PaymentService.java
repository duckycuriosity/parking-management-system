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
import vio.barie.core.dto.parking.PaymentMessage;
import vio.barie.core.dto.parking.PaymentSuccessCommand;
import vio.barie.core.dto.payment.*;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentGateway;
import vio.barie.core.utils.MegaPaySignatureUtil;
import vio.barie.core.utils.Sanitize;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.hutool.core.convert.Convert.hexToBytes;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway; // inject adapter vào đây

    @Transactional
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
        // Mark any previous PENDING payment for this session as FAILED (superseded)
        failPendingPaymentsForSession(sessionId);

        PaymentEntity payment = new PaymentEntity();

        payment.setInvoiceNo(invoiceNo);
        payment.setAmount(amount);
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setPayType(payType);
        payment.setDescription(Sanitize.clean(description));
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
        // Mark any previous PENDING payment for this ticket as FAILED (superseded)
        failPendingPaymentsForTicket(ticketId);

        PaymentEntity payment = new PaymentEntity();

        payment.setInvoiceNo(invoiceNo);
        payment.setAmount(amount);
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setPayType(payType);
        payment.setDescription(Sanitize.clean(description));
        payment.setStatus("PENDING");
        payment.setCallbackType(PaymentCallbackType.TICKET.name());

        JSONObject context = new JSONObject();
        context.set("ticketId", ticketId);
//        context.set("customerId", customerId);
        payment.setCallbackContext(context);

        return paymentRepository.save(payment);
    }

    public CreatePaymentResponse createPaymentLink(CreatePaymentRequest req){
        return paymentGateway.createPaymentLink(req);
    };

    public void handleCallback(String body){
        paymentGateway.handleCallback(body);
    };

    public PaymentInquiryResponse inquiryByInvoiceNo(String invoiceNo){
        return paymentGateway.inquiryByInvoiceNo(invoiceNo);
    };

    private void failPendingPaymentsForSession(Long sessionId) {
        if (sessionId == null) return;
        java.util.List<PaymentEntity> stale =
                paymentRepository.findPendingBySessionId(String.valueOf(sessionId));
        if (stale.isEmpty()) return;
        for (PaymentEntity p : stale) {
            p.setStatus("FAILED");
            p.setResultMessage("Superseded by new payment attempt");
        }
        paymentRepository.saveAll(stale);
        log.info("Marked {} stale PENDING payment(s) as FAILED for sessionId={}", stale.size(), sessionId);
    }

    private void failPendingPaymentsForTicket(Long ticketId) {
        if (ticketId == null) return;
        java.util.List<PaymentEntity> stale =
                paymentRepository.findPendingByTicketId(String.valueOf(ticketId));
        if (stale.isEmpty()) return;
        for (PaymentEntity p : stale) {
            p.setStatus("FAILED");
            p.setResultMessage("Superseded by new payment attempt");
        }
        paymentRepository.saveAll(stale);
        log.info("Marked {} stale PENDING payment(s) as FAILED for ticketId={}", stale.size(), ticketId);
    }
}
