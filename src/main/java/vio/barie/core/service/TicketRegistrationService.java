package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.PlateCreateRequest;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.ticket.AdminMonthlyTicketRegistrationRequest;
import vio.barie.core.dto.ticket.TicketRegistrationResponseDTO;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.repository.VehicleRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketRegistrationService {

    private final ProfileService profileService;
    private final PlateService plateService;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final VehicleRepository vehicleRepository;
    private final TicketRepository ticketRepository;
    private final PaymentService paymentService;
    private final CalculatorService calculatorService;
    private final TicketSummaryCacheService summaryCacheService;

    /**
     * Admin đăng ký vé tháng/quý cho khách hàng
     * Xử lý: Tạo/cập nhật customer, đăng ký face, tạo vehicle, đăng ký plate, tạo
     * ticket
     */
    @Transactional
    public TicketRegistrationResponseDTO registerMonthlyTicket(
            AdminMonthlyTicketRegistrationRequest req,
            MultipartFile faceImage) throws IOException {

        log.info("Starting monthly ticket registration for customer: {}, plate: {}",
                req.getName(), req.getVehiclePlateNumber());

        // 1️⃣ Validate input
        validateRequest(req, faceImage);

        // 2️⃣ Create or get customer
        boolean isCustomerExisting = getOrCreateCustomer(
                req.getName(),
                req.getCustomerPhone(),
                req.getCustomerEmail(),
                req.getCustomerZaloId());
        if (isCustomerExisting) {
            log.error("Customer with phone {} is already existing", req.getCustomerPhone());
            throw new ServiceException("Customer with phone " + req.getCustomerPhone() + " already exists", 400);
        }

        // 3️⃣ Register face via AI
        AiProfileResponse profile = profileService.create(
                req.getName(),
                0, // groupId = 0 (default)
                faceImage);
        log.info("Face registered successfully: profileId={}, uuid={}",
                profile.getId(), profile.getUuid());

        Optional<ParkingCustomerEntity> customer = parkingCustomerRepository.findByAiProfileId(profile.getId());
        if (customer.isEmpty()) {
            log.error("Customer not found after creation: name={}, phone={}", req.getName(), req.getCustomerPhone());
            throw new ServiceException("Customer not found after creation", 500);
        }

        // 4️⃣ Validate plate not already registered
        if (vehicleRepository.existsByPlateNumber(req.getVehiclePlateNumber())) {
            throw new ServiceException("Plate already registered: " + req.getVehiclePlateNumber(), 400);
        }

        // 5️⃣ Register plate via AI
        PlateCreateRequest plateReq = new PlateCreateRequest();
        plateReq.setPlate(req.getVehiclePlateNumber());
        plateReq.setType(req.getVehicleType());
        plateReq.setEnabled(true);

        AiPlateResponse plate = plateService.create(plateReq);
        log.info("Plate registered successfully: plateId={}, uuid={}",
                plate.getId(), plate.getUuid());

        // 6️⃣ Create vehicle
        String normalizedVehicleType = normalizeVehicleType(req.getVehicleType());
        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setPlateNumber(req.getVehiclePlateNumber());
        vehicle.setAiIdentityPlateId(plate.getUuid());
        vehicle.setVehicleType(normalizedVehicleType);
        vehicle.setInfo("{}"); // TODO: store plate metadata if needed
        VehicleEntity savedVehicle = vehicleRepository.save(vehicle);
        log.debug("Vehicle created: vehicleId={}", savedVehicle.getId());

        // 7️⃣ Create ticket
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = calculateExpireDate(req.getTicketType());

        TicketEntity ticket = new TicketEntity();
        ticket.setVehicle(savedVehicle);
        ticket.setCustomer(customer.orElse(null));
        ticket.setTicketType(req.getTicketType());
        ticket.setRegisterAt(now);
        ticket.setExpireAt(expireAt);
        ticket.setStatus(TicketStatus.DEAD); // Ticket dead cho đến khi người dùng thanh toán
        ticket.setStatus(req.getStatus() != null ? req.getStatus() : TicketStatus.DEAD);

        TicketEntity savedTicket = ticketRepository.save(ticket);

        if (savedTicket.getStatus() == TicketStatus.ACTIVE) {
            summaryCacheService.increment("total");
            summaryCacheService.increment("active");

            // nếu vé mới đăng ký mà có hạn < 5 ngày (ít xảy ra nhưng vẫn nên check)
            if (savedTicket.getExpireAt().isBefore(LocalDateTime.now().plusDays(5))) {
                summaryCacheService.increment("expiring_soon");
            }
        } else if (savedTicket.getStatus() == TicketStatus.INACTIVE) {
            summaryCacheService.increment("total");
            summaryCacheService.increment("expired");
        }
        log.info("Ticket created successfully: ticketId={}, expireAt={}",
                savedTicket.getId(), expireAt);

        // 8️⃣ Build and return response
        TicketRegistrationResponseDTO response = TicketRegistrationResponseDTO.builder()
                .ticketId(savedTicket.getId())
                .ticketType(req.getTicketType().name())
                .ticketStatus(savedTicket.getStatus().name())
                .registeredAt(savedTicket.getRegisterAt())
                .expireAt(savedTicket.getExpireAt())
                .customerId(customer.map(ParkingCustomerEntity::getId).orElse(null))
                .customerName(customer.map(ParkingCustomerEntity::getUsername).orElse(null))
                .customerPhone(customer.map(ParkingCustomerEntity::getPhoneNumber).orElse(null))
                .vehicleId(savedVehicle.getId())
                .plateNumber(savedVehicle.getPlateNumber())
                .vehicleType(savedVehicle.getVehicleType())
                .profileId(profile.getId())
                .notes(req.getNotes())
                .message("Monthly ticket registered successfully. Ticket is now active.")
                .build();

        log.info("Monthly ticket registration completed successfully: ticketId={}, customerId={}, vehicleId={}",
                savedTicket.getId(), customer, savedVehicle.getId());

        return response;
    }

    public String getTicketPayment(Long ticketId) {
        TicketEntity ticket = ticketRepository.findById(ticketId).orElseThrow(
                () -> new ServiceException("Không tìm ticket tương ", 404)
        );

        // chỉ cho phép gọi mã này nếu
        if (ticket.getStatus().equals(TicketStatus.ACTIVE.name())) {
            return null;
        }

        BigDecimal amount = calculatorService.getTicketPrice(ticket);
        // tao QR
        CreatePaymentRequest payReq = new CreatePaymentRequest();
        payReq.setAmount(amount);
        payReq.setGoodsName("Phi gui xe");
        payReq.setDescription("Phi dang ki ve gui xe");
        CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);

        // tao payment
        paymentService.createTicketPayment(
                qr.getInvoiceNo(),
                qr.getAmount(),
                qr.getPayType(),
                "Phi dang ki ve gui xe",
                ticket.getId()
//                ticket.getCustomerId()
        );
        return qr.getQrCode();
    }

    /**
     * Tạo mới hoặc lấy customer hiện có
     * Prioritize by phone number
     */
    private boolean getOrCreateCustomer(
            String name,
            String phone,
            String email,
            String zaloId) {

        // Try find by phone number
        Optional<ParkingCustomerEntity> existing = parkingCustomerRepository
                .findByPhoneNumber(phone);

        return existing.isPresent();
    }

    /**
     * Calculate ticket expiration date based on type
     */
    private LocalDateTime calculateExpireDate(TicketType ticketType) {
        LocalDateTime now = LocalDateTime.now();

        if (ticketType == TicketType.MONTH) {
            return now.plusMonths(1);
        } else if (ticketType == TicketType.QUARTER) {
            return now.plusMonths(3);
        } else if (ticketType == TicketType.YEAR) {
            return now.plusYears(1);
        }

        // Default: 1 month
        return now.plusMonths(1);
    }

    /**
     * Validate request data
     */
    private void validateRequest(AdminMonthlyTicketRegistrationRequest req, MultipartFile faceImage) {

        // Image validation
        if (faceImage == null || faceImage.isEmpty()) {
            throw new ServiceException("Face image is required", 400);
        }

        String contentType = faceImage.getContentType();
        if (!isValidImageType(contentType)) {
            throw new ServiceException("Invalid image type. Only JPEG and PNG are allowed", 400);
        }

        long maxSize = 10 * 1024 * 1024; // 10MB
        if (faceImage.getSize() > maxSize) {
            throw new ServiceException("Image size exceeds 10MB limit", 400);
        }

        // Ticket type validation
        if (req.getTicketType() != TicketType.MONTH
                && req.getTicketType() != TicketType.QUARTER
                && req.getTicketType() != TicketType.YEAR) {
            throw new ServiceException("Invalid ticket type: " + req.getTicketType(), 400);
        }

        // Vehicle type validation against DB constraint: CAR, MOTOBIKE, OTHER
        if (normalizeVehicleType(req.getVehicleType()) == null) {
            throw new ServiceException(
                    "Invalid vehicleType. Supported: CAR, MOTOBIKE, OTHER (aliases: MOTORBIKE, SEDAN, SUV, TRUCK)",
                    400);
        }
    }

    private String normalizeVehicleType(String rawVehicleType) {
        if (rawVehicleType == null || rawVehicleType.isBlank()) {
            return null;
        }

        String value = rawVehicleType.trim().toUpperCase(Locale.ROOT);
        if ("CAR".equals(value) || "SEDAN".equals(value) || "SUV".equals(value) || "TRUCK".equals(value)) {
            return "CAR";
        }
        if ("MOTOBIKE".equals(value) || "MOTORBIKE".equals(value) || "BIKE".equals(value)) {
            return "MOTOBIKE";
        }
        if ("OTHER".equals(value)) {
            return "OTHER";
        }
        return null;
    }

    /**
     * Check if content type is valid image
     */
    private boolean isValidImageType(String contentType) {
        return contentType != null && (contentType.equals("image/jpeg") ||
                contentType.equals("image/png") ||
                contentType.equals("image/jpg"));
    }
}