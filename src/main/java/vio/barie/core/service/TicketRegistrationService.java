package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.common.PaymentType;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.PlateCreateRequest;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.payment.PaymentInquiryResponse;
import vio.barie.core.dto.payment.SePayTransaction;
import vio.barie.core.dto.ticket.AdminMonthlyTicketRegistrationRequest;
import vio.barie.core.dto.ticket.RenewTicketRequest;
import vio.barie.core.dto.ticket.TicketDTO;
import vio.barie.core.dto.ticket.TicketRegistrationResponseDTO;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.PaymentRepository;
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
    private final PaymentRepository paymentRepository;
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
                req.getPhoneNumber(),
                req.getCustomerEmail());
        if (isCustomerExisting) {
            log.error("Customer with phone {} is already existing", req.getPhoneNumber());
            throw new ServiceException("Customer with phone " + req.getPhoneNumber() + " already exists", 400);
        }

        // 3️⃣ Register face via AI
        AiProfileResponse profile = profileService.create(
                req.getName(),
                0, // groupId = 0 (default)
                faceImage,
                req.getCustomerEmail());
        log.info("Face registered successfully: profileId={}, uuid={}",
                profile.getId(), profile.getUuid());

        Optional<ParkingCustomerEntity> customer = parkingCustomerRepository.findByAiProfileId(profile.getId());
        if (customer.isEmpty()) {
            log.error("Customer not found after creation: name={}, phone={}", req.getName(), req.getPhoneNumber());
            throw new ServiceException("Customer not found after creation", 400);
        }

        // Tìm xe theo biển số
        Optional<VehicleEntity> vehicleRecord = vehicleRepository.findByPlateNumber(req.getVehiclePlateNumber());
        VehicleEntity vehicle;
        if (vehicleRecord.isPresent()) {
            ticketRepository.findTicketByVehicleId(vehicleRecord.get().getId()).ifPresent(existingTicket -> {
                if (existingTicket.getStatus() != TicketStatus.DEAD) {
                    throw new ServiceException(
                            "Xe " + req.getVehiclePlateNumber() + " đã có vé đang hiệu lực. Vui lòng dùng API gia hạn.", 400);
                }
            });
            vehicle = vehicleRecord.get();
        } else {
            // 5️⃣ Xe chưa có → đăng ký biển số với AI và tạo mới
            PlateCreateRequest plateReq = new PlateCreateRequest();
            plateReq.setPlate(req.getVehiclePlateNumber());
            plateReq.setType(req.getVehicleType());
            plateReq.setEnabled(true);

            AiPlateResponse plate = plateService.create(plateReq);
            log.info("Plate registered successfully: plateId={}, uuid={}", plate.getId(), plate.getUuid());

            // 6️⃣ Create vehicle
            String normalizedVehicleType = normalizeVehicleType(req.getVehicleType());
            VehicleEntity newVehicle = new VehicleEntity();
            newVehicle.setPlateNumber(req.getVehiclePlateNumber());
            newVehicle.setAiIdentityPlateId(plate.getUuid());
            newVehicle.setVehicleType(normalizedVehicleType);
            newVehicle.setInfo("{}");

            vehicle = vehicleRepository.save(newVehicle);
            log.debug("Vehicle created: vehicleId={}", vehicle.getId());
        }

        // 7️⃣ Create ticket
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = calculateExpireDate(req.getTicketType(), req.getStartDate());

        TicketEntity ticket = new TicketEntity();
        ticket.setVehicle(vehicle);
        ticket.setCustomer(customer.orElse(null));
        ticket.setTicketType(req.getTicketType());
        ticket.setRegisterAt(now);
        ticket.setStartDate(req.getStartDate());
        ticket.setExpireAt(expireAt);
        ticket.setPhoneNumber(req.getPhoneNumber());
        ticket.setNotes(req.getNotes());
        ticket.setStatus(TicketStatus.DEAD); // Ticket dead cho đến khi người dùng thanh toán

        TicketEntity savedTicket = ticketRepository.save(ticket);

        summaryCacheService.increment("dead");
        log.info("Ticket created with DEAD status: ticketId={}, expireAt={}", savedTicket.getId(), expireAt);

        // 8️⃣ Build and return response
        TicketRegistrationResponseDTO response = TicketRegistrationResponseDTO.builder()
                .ticketId(savedTicket.getId())
                .ticketType(req.getTicketType().name())
                .ticketStatus(savedTicket.getStatus().name())
                .registeredAt(savedTicket.getRegisterAt())
                .expireAt(savedTicket.getExpireAt())
                .startDate(savedTicket.getStartDate())
                .customerId(customer.map(ParkingCustomerEntity::getId).orElse(null))
                .customerName(customer.map(ParkingCustomerEntity::getUsername).orElse(null))
                .vehicleId(vehicle.getId())
                .plateNumber(vehicle.getPlateNumber())
                .vehicleType(vehicle.getVehicleType())
                .profileId(profile.getId())
                .notes(req.getNotes())
                .customerImageUrl(customer.map(ParkingCustomerEntity::getImageUrl).orElse(null))
                .email(customer.map(ParkingCustomerEntity::getEmail).orElse(""))
                .phoneNumber(savedTicket.getPhoneNumber())
                .notes(savedTicket.getNotes())
                .message("Monthly ticket registered successfully. Ticket is now active.")
                .build();

        log.info("Monthly ticket registration completed successfully: ticketId={}, customerId={}, vehicleId={}",
                savedTicket.getId(), customer, vehicle.getId());

        return response;
    }

    public JSONObject getTicketPayment(Long ticketId) {
        getLatestStatus(ticketId);

        TicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(
                () -> new ServiceException("Không tìm ticket tương ", 404));

        // chỉ cho phép gọi mã này nếu
        if (ticket.getStatus() == TicketStatus.ACTIVE) {
            log.info("Ticket id={} đã ACTIVE, không cần thanh toán", ticketId);
            throw new ServiceException("Vé đã thanh toán! Xin vui lòng không sinh giao dịch mới cho cái vé này.");
        }

        BigDecimal amount = calculatorService.getTicketPrice(ticket);
        // tao QR
        CreatePaymentRequest payReq = new CreatePaymentRequest();
        payReq.setAmount(amount);
        payReq.setGoodsName("Phi gui xe");
        payReq.setDescription("THANH TOAN VE GUI XE " + ticket.getVehicle().getPlateNumber());
        CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);

        // tao payment
        paymentService.createTicketPayment(
                qr.getInvoiceNo(),
                qr.getAmount(),
                qr.getPayType(),
                "Phi dang ki ve gui xe",
                ticket.getId()
                // ticket.getCustomerId()
        );
        JSONObject result = new JSONObject();
        result.set("qr", qr.getPaymentLink());
        result.set("desc", "THANH TOAN VE GUI XE " + ticket.getVehicle().getPlateNumber());
        result.set("bank_name", "MB");
        result.set("bank_acc_num", "0843233326");
        result.set("amount", qr.getAmount());
        result.set("company_name", "VioTech");
        return result;
    }

    public TicketDTO getLatestStatus(Long ticketId) {
        PaymentEntity payment = paymentRepository
                .findLatestByTikcetId(String.valueOf(ticketId))
                .orElse(null);
        if (payment == null) {
            log.info("getLatestStatus: không có payment nào cho ticketId={}", ticketId);
        } else if (PaymentType.PENDING.name().equals(payment.getStatus())) {
            // nếu còn PENDING, thử check SePay xem tiền đã về chưa
            try {
                PaymentInquiryResponse inquiryResponse = paymentService.inquiryByInvoiceNo(payment.getInvoiceNo());
                if (inquiryResponse != null) {
                    log.info("getLatestStatus: ticketId={} có giao dịch SePay khớp invoice={}, replay handler",
                            ticketId, payment.getInvoiceNo());
                    paymentService.handleCallback(inquiryResponse.getMetadata());
                }
            } catch (Exception e) {
                log.error("getLatestStatus replay error: {}", e.getMessage(), e);
            }
        }
        // Lấy trạng thái ticket hiện tại
        return ticketRepository.findById(ticketId)
                .map(TicketEntity::convertToDTO)
                .orElseThrow(() -> new ServiceException("Không tìm thấy vé tháng tương ứng!",404));
    }

    @Transactional
    public TicketRegistrationResponseDTO renewMonthlyTicket(RenewTicketRequest req) {
        if (req.getStartDate().isBefore(LocalDateTime.now()))
            throw new ServiceException("Thời gian ko được là quá khứ!");

        // update latest status first
        getLatestStatus(req.getId());

        TicketEntity ticket = ticketRepository.findById(req.getId())
                .orElseThrow(() -> new ServiceException(
                        "Không tìm thấy vé cho id: " + req.getId()
                                + ". Vui lòng dùng API đăng ký mới.", 404));

        TicketStatus currentStatus = ticket.getStatus();

        if (currentStatus.equals(TicketStatus.ACTIVE)) {
            throw new ServiceException("Vé hiện tại vẫn còn hiệu lực. Xin vui lòng thử lại sau khi vé hết hạn.");
        } else if (currentStatus.equals(TicketStatus.DEAD) || currentStatus.equals(TicketStatus.INACTIVE)) {
            ParkingCustomerEntity customer = ticket.getCustomer();
            VehicleEntity vehicle = ticket.getVehicle();

            TicketRegistrationResponseDTO result = TicketRegistrationResponseDTO.builder()
                    .ticketId(ticket.getId())
                    .ticketType(req.getTicketType().name())
                    .ticketStatus(ticket.getStatus().name())
                    .customerId(customer != null ? customer.getId() : null)
                    .customerName(customer != null ? customer.getUsername() : null)
                    .profileId(customer.getAiProfileId())
                    .vehicleId(vehicle.getId())
                    .plateNumber(vehicle.getPlateNumber())
                    .vehicleType(vehicle.getVehicleType())
                    .startDate(req.getStartDate())
                    .registeredAt(ticket.getRegisterAt())
                    .expireAt(ticket.getExpireAt())
                    .notes(req.getNotes())
                    .phoneNumber(ticket.getPhoneNumber())
                    .build();
            // vé DEAD => chua tung duoc thanh toan
            // vé INACTIVE => ve het han
            // chinh lai thoi gian
            ticket.setStartDate(req.getStartDate());
            ticket.setRegisterAt(req.getStartDate());
            ticket.setExpireAt(calculateExpireDate(req.getTicketType(), req.getStartDate()));

            ticket = ticketRepository.save(ticket);
            log.info("[renewMonthlyTicket] Vé mới id={} tạo thành công, expireAt={}", ticket.getId(), ticket.getExpireAt());

            result.setStartDate(ticket.getStartDate());
            result.setRegisteredAt(ticket.getRegisterAt());
            result.setExpireAt(ticket.getExpireAt());
            result.setMessage("Thay đổi vé tháng thành công. Tiến hành thanh toán để kích hoạt vé.");
            return result;
        } else {
            throw new ServiceException("Status của vé nằm ngoài phạm vi hồ trợ của hệ thống: "+currentStatus,403);
        }
    }

    /**
     * Tạo mới hoặc lấy customer hiện có
     * Prioritize by phone number
     */
    private boolean getOrCreateCustomer(
            String name,
            String phone,
            String email) {

        // Try find by phone number
        Optional<ParkingCustomerEntity> existing = parkingCustomerRepository
                .findByPhoneNumber(phone);

        return existing.isPresent();
    }

    /**
     * Calculate ticket expiration date based on type
     */
    private LocalDateTime calculateExpireDate(TicketType ticketType, LocalDateTime startDate) {
        LocalDateTime now = (startDate != null) ? startDate : LocalDateTime.now();

        if (ticketType == TicketType.MONTH) {
            return now.plusMonths(1);
        } else if (ticketType == TicketType.QUARTER) {
            return now.plusMonths(3);
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
                && req.getTicketType() != TicketType.QUARTER) {
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