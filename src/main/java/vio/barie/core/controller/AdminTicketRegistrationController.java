package vio.barie.core.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.ticket.AdminMonthlyTicketRegistrationRequest;
import vio.barie.core.dto.ticket.TicketRegistrationResponseDTO;
import vio.barie.core.service.TicketRegistrationService;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/admin/monthly-ticket-registration")
@RequiredArgsConstructor
@Tag(name = "Admin Ticket Registration", description = "APIs for admin to register monthly tickets for customers")
public class AdminTicketRegistrationController {

    private final TicketRegistrationService ticketRegistrationService;

    /**
     * Admin đăng ký vé tháng cho khách hàng
     * <p>
     * Request dùng multipart/form-data với các field form và một file image.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    // @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).ONLY_ORG_EDIT())")
    @Operation(summary = "Register monthly ticket for customer", description = "Admin registers a monthly or quarterly ticket for a customer. "
            +
            "Includes face registration, vehicle registration, and ticket creation.")
    public Result<TicketRegistrationResponseDTO> registerMonthlyTicket(
            @ParameterObject
            @Valid @ModelAttribute AdminMonthlyTicketRegistrationRequest request,
            @RequestPart("image") MultipartFile image) {

        log.info("Admin registering monthly ticket - customer: {}, plate: {}, ticketType: {}",
                request.getName(), request.getVehiclePlateNumber(), request.getTicketType());

        try {
            TicketRegistrationResponseDTO response = ticketRegistrationService
                    .registerMonthlyTicket(request, image);

            log.info("Monthly ticket registered successfully - ticketId: {}, customerId: {}",
                    response.getTicketId(), response.getCustomerId());

            return Result.ok("Monthly ticket registered successfully",
                    response);

        } catch (IOException ex) {
            log.error("IO error while processing face image: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to process face image: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Error registering monthly ticket - customer: {}, error: {}",
                    request.getName(), ex.getMessage(), ex);
            throw ex;
        }
    }

    @GetMapping("/qrcode/{ticketId}")
    public Result<String> getTicketRegistrationQrcode(@PathVariable Long ticketId) {
        String qr = ticketRegistrationService.getTicketPayment(ticketId);
        return new Result<>(
                200,
                "qrcode",
                qr);
    }
}
