package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.PlateCreateRequest;
import vio.barie.core.dto.ticket.AdminMonthlyTicketRegistrationRequest;
import vio.barie.core.dto.ticket.TicketRegistrationResponseDTO;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.repository.VehicleRepository;

import java.io.IOException;
import java.time.LocalDateTime;
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
                req.getCustomerName(), req.getVehiclePlateNumber());

        // 1️⃣ Validate input
        validateRequest(req, faceImage);

        // 2️⃣ Create or get customer
        ParkingCustomerEntity customer = getOrCreateCustomer(
                req.getCustomerName(),
                req.getCustomerPhone(),
                req.getCustomerEmail(),
                req.getCustomerZaloId());
        log.debug("Customer resolved/created: customerId={}", customer.getId());

        // 3️⃣ Register face via AI
        AiProfileResponse profile = profileService.create(
                req.getCustomerName(),
                0, // groupId = 0 (default)
                faceImage);
        log.info("Face registered successfully: profileId={}, uuid={}",
                profile.getId(), profile.getUuid());

        // Update customer with profile info
        customer.setAiProfileId(profile.getId());
        customer.setAiIdentityId(profile.getUuid());
        customer.setUsername(req.getCustomerName());
        customer = parkingCustomerRepository.save(customer);

        // 4️⃣ Validate plate not already registered
        if (vehicleRepository.existsByPlateNumber(req.getVehiclePlateNumber())) {
            throw new ServiceException("Plate already registered: " + req.getVehiclePlateNumber(), 400);
        }

        // 5️⃣ Register plate via AI
        PlateCreateRequest plateReq = new PlateCreateRequest();
        plateReq.setPlate(req.getVehiclePlateNumber());
        plateReq.setType(req.getPlateType());
        plateReq.setEnabled(true);

        AiPlateResponse plate = plateService.create(plateReq);
        log.info("Plate registered successfully: plateId={}, uuid={}",
                plate.getId(), plate.getUuid());

        // 6️⃣ Create vehicle
        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setPlateNumber(req.getVehiclePlateNumber());
        vehicle.setAiIdentityPlateId(plate.getUuid());
        vehicle.setVehicleType(req.getVehicleType());
        vehicle.setInfo("{}"); // TODO: store plate metadata if needed
        VehicleEntity savedVehicle = vehicleRepository.save(vehicle);
        log.debug("Vehicle created: vehicleId={}", savedVehicle.getId());

        // 7️⃣ Create ticket
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = calculateExpireDate(req.getTicketType());

        TicketEntity ticket = new TicketEntity();
        ticket.setVehicle(savedVehicle);
        ticket.setRegisterAt(now);
        ticket.setExpireAt(expireAt);
        ticket.setStatus("ACTIVE"); // Ticket ngay lập tức active

        TicketEntity savedTicket = ticketRepository.save(ticket);
        log.info("Ticket created successfully: ticketId={}, expireAt={}",
                savedTicket.getId(), expireAt);

        // 8️⃣ Build and return response
        TicketRegistrationResponseDTO response = TicketRegistrationResponseDTO.builder()
                .ticketId(savedTicket.getId())
                .ticketType(req.getTicketType())
                .ticketStatus(savedTicket.getStatus())
                .registeredAt(savedTicket.getRegisterAt())
                .expireAt(savedTicket.getExpireAt())
                .customerId(customer.getId())
                .customerName(customer.getUsername())
                .customerPhone(customer.getPhoneNumber())
                .vehicleId(savedVehicle.getId())
                .plateNumber(savedVehicle.getPlateNumber())
                .vehicleType(savedVehicle.getVehicleType())
                .profileId(profile.getId())
                .notes(req.getNotes())
                .message("Monthly ticket registered successfully. Ticket is now active.")
                .build();

        log.info("Monthly ticket registration completed successfully: ticketId={}, customerId={}, vehicleId={}",
                savedTicket.getId(), customer.getId(), savedVehicle.getId());

        return response;
    }

    /**
     * Tạo mới hoặc lấy customer hiện có
     * Prioritize by phone number
     */
    private ParkingCustomerEntity getOrCreateCustomer(
            String name,
            String phone,
            String email,
            String zaloId) {

        // Try find by phone number
        Optional<ParkingCustomerEntity> existing = parkingCustomerRepository
                .findByPhoneNumber(phone);

        if (existing.isPresent()) {
            log.debug("Customer already exists with phone: {}", phone);
            return existing.get();
        }

        // Create new customer
        log.debug("Creating new customer: name={}, phone={}", name, phone);
        ParkingCustomerEntity customer = new ParkingCustomerEntity();
        customer.setUsername(name);
        customer.setPhoneNumber(phone);
        customer.setZaloId(zaloId);

        return parkingCustomerRepository.save(customer);
    }

    /**
     * Calculate ticket expiration date based on type
     */
    private LocalDateTime calculateExpireDate(String ticketType) {
        LocalDateTime now = LocalDateTime.now();

        if ("MONTH".equals(ticketType)) {
            return now.plusMonths(1);
        } else if ("QUARTER".equals(ticketType)) {
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
        if (!"MONTH".equals(req.getTicketType()) && !"QUARTER".equals(req.getTicketType())) {
            throw new ServiceException("Invalid ticket type: " + req.getTicketType(), 400);
        }

        // Vehicle type validation
        if (req.getVehicleType() == null || req.getVehicleType().isBlank()) {
            throw new ServiceException("Vehicle type is required", 400);
        }
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
