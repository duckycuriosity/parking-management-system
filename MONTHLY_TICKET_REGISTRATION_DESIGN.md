# Luồng Đăng Ký Vé Tháng (Monthly Ticket Registration) - Design Document

## 1. Tổng Quan

**Admin thay thế khách hàng đăng ký vé tháng/quý.**

Admin sẽ cung cấp tất cả thông tin của khách hàng trong **một form hoặc một API call duy nhất**:
1. **Khách hàng:** Tên, Zalo ID, số điện thoại (hoặc chọn từ danh sách đã tồn tại)
2. **Loại vé:** MONTH / QUARTER
3. **Ảnh khuôn mặt:** Upload từ admin
4. **Thông tin xe:** Biển số, loại xe, loại biển

Backend sẽ xử lý tất cả các bước:
- Tạo/cập nhật customer + profile (đăng ký mặt)
- Tạo/cập nhật vehicle + plate (đăng ký xe)
- Tạo ticket record
- (Optional) Tạo payment link hoặc thanh toán trực tiếp

---

## 2. Kiến Trúc Luồng Chi Tiết

### 2.1 Một API Call Duy Nhất - Admin Đăng Ký Vé Tháng

**Mục đích:** Admin submit toàn bộ thông tin khách hàng + xe + ảnh → Backend xử lý tất cả → Ticket được tạo

**Endpoint:**
```
POST /api/admin/monthly-ticket-registration
Content-Type: multipart/form-data
```

**Request Body (multipart):**
```
customerName: "Nguyễn Văn A"        (text)
customerPhone: "0987654321"         (text)
customerZaloId: "0987654321"        (text, optional)
ticketType: "MONTH"                 (text: "MONTH" hoặc "QUARTER")
vehiclePlateNumber: "29A12345"      (text)
vehicleType: "SEDAN"                (text)
plateType: "NORMAL"                 (text)
faceImage: <binary image file>      (file)
paymentMethod: "QR"                 (text: "QR", "BANK_TRANSFER", "CASH")
notes: "Khách ghi tên anh A"         (text, optional)
```

**Response (ngay lập tức):**
```json
{
  "status": "SUCCESS",
  "message": "Monthly ticket registered successfully",
  "data": {
    "ticketId": 555,
    "registrationId": "REG-20260513-001",
    "ticketType": "MONTH",
    "ticketStatus": "ACTIVE",
    "customerId": 789,
    "customerName": "Nguyễn Văn A",
    "vehicleId": 456,
    "plateNumber": "29A12345",
    "profileId": 12345,
    "amount": 1500000,
    "currency": "VND",
    "paymentStatus": "SUCCESS",
    "invoiceNo": "INV-20260513-001",
    "registeredAt": "2026-05-13T10:00:00",
    "expireAt": "2026-06-13T10:00:00",
    "qrCode": "data:image/png;base64,...",  // optional: nếu là QR payment
    "paymentLink": "https://...",             // optional: nếu là QR payment
    "message": "Ticket activated successfully"
  }
}
```

**Response (nếu thanh toán chưa thanh toán):**
```json
{
  "status": "SUCCESS",
  "message": "Monthly ticket registered, payment pending",
  "data": {
    "ticketId": 555,
    "registrationId": "REG-20260513-001",
    "ticketStatus": "INACTIVE",  // chưa active cho đến khi thanh toán
    "paymentStatus": "PENDING",
    "paymentId": 999,
    "invoiceNo": "INV-20260513-001",
    "amount": 1500000,
    "qrCode": "data:image/png;base64,...",
    "paymentLink": "https://...",
    "message": "Ticket registered but not yet activated. Awaiting payment."
  }
}
```

**Backend Flow:**

```
1. Validate input
   ├─ ticketType in [MONTH, QUARTER]
   ├─ vehiclePlateNumber format valid
   ├─ Image file present & valid
   └─ CustomerName not empty

2. Create / Update ParkingCustomer
   ├─ Check if customer exists (by phone)
   └─ Create new if not exists

3. Register Face via ProfileService.create()
   ├─ Call AI: POST profile with image
   └─ Get profileId from AI response

4. Create VehicleEntity
   ├─ Check plate not already registered
   └─ Save with AI plate info

5. Create TicketEntity
   ├─ ticketType = MONTH/QUARTER
   ├─ registerAt = now()
   ├─ expireAt = now() + (30 or 90 days)
   └─ status = ACTIVE (if instant) or INACTIVE (if payment pending)

6. Handle Payment (based on paymentMethod)
   ├─ If "CASH": status = ACTIVE (admin đã collect tiền)
   ├─ If "QR": Create payment link, ticket INACTIVE until callback
   └─ If "BANK_TRANSFER": ticket INACTIVE, manual verify

7. Return response
```

**Service Method (`TicketRegistrationService`):**

```java
@Transactional
public TicketRegistrationResponseDTO registerMonthlyTicket(
        AdminMonthlyTicketRegistrationRequest req,
        MultipartFile faceImage) throws IOException {
    
    // 1. Validate
    validateRequest(req);
    
    // 2. Create or get customer
    ParkingCustomerEntity customer = getOrCreateCustomer(
        req.getCustomerName(),
        req.getCustomerPhone(),
        req.getCustomerZaloId()
    );
    
    // 3. Register face
    AiProfileResponse profile = profileService.create(
        req.getCustomerName(),
        0,  // groupId
        faceImage
    );
    
    // Update customer with profile
    customer.setAiProfileId(profile.getId());
    customer.setAiIdentityId(profile.getUuid());
    customer.setUsername(req.getCustomerName());
    parkingCustomerRepository.save(customer);
    
    // 4. Register plate & create vehicle
    PlateCreateRequest plateReq = new PlateCreateRequest();
    plateReq.setPlate(req.getVehiclePlateNumber());
    plateReq.setType(req.getPlateType());
    plateReq.setEnabled(true);
    
    AiPlateResponse plate = plateService.create(plateReq);
    
    VehicleEntity vehicle = new VehicleEntity();
    vehicle.setPlateNumber(req.getVehiclePlateNumber());
    vehicle.setAiIdentityPlateId(plate.getUuid());
    vehicle.setVehicleType(req.getVehicleType());
    VehicleEntity savedVehicle = vehicleRepository.save(vehicle);
    
    // 5. Calculate price & create ticket
    int amount = calculateTicketPrice(req.getTicketType());
    LocalDateTime expireAt = calculateExpireDate(req.getTicketType());
    
    TicketEntity ticket = new TicketEntity();
    ticket.setVehicle(savedVehicle);
    ticket.setRegisterAt(LocalDateTime.now());
    ticket.setExpireAt(expireAt);
    ticket.setStatus("ACTIVE");  // Default active
    
    // 6. Handle payment based on method
    PaymentEntity payment = null;
    String paymentStatus = "NONE";
    String qrCode = null;
    String paymentLink = null;
    
    if ("CASH".equals(req.getPaymentMethod())) {
        // Cash: admin already collected
        payment = createPayment(amount, "CASH", "SUCCESS");
        paymentStatus = "SUCCESS";
        ticket.setStatus("ACTIVE");
        
    } else if ("QR".equals(req.getPaymentMethod())) {
        // QR: create link, ticket inactive until payment
        payment = createPayment(amount, "QR", "PENDING");
        CreatePaymentResponse qrResponse = paymentService.createPaymentLink(
            new CreatePaymentRequest()
                .setAmount(amount)
                .setDescription("Monthly ticket - " + req.getTicketType())
        );
        qrCode = qrResponse.getQrCode();
        paymentLink = qrResponse.getPaymentLink();
        paymentStatus = "PENDING";
        ticket.setStatus("INACTIVE");  // Chờ thanh toán
        
    } else if ("BANK_TRANSFER".equals(req.getPaymentMethod())) {
        // Bank transfer: ticket inactive until admin verify
        payment = createPayment(amount, "BANK_TRANSFER", "PENDING");
        paymentStatus = "PENDING";
        ticket.setStatus("INACTIVE");
    }
    
    // 7. Save ticket & payment
    TicketEntity savedTicket = ticketRepository.save(ticket);
    if (payment != null) {
        payment = paymentRepository.save(payment);
    }
    
    // 8. Create session record (for tracking)
    createRegistrationSession(req, savedTicket, customer, savedVehicle, payment);
    
    // 9. Build response
    return TicketRegistrationResponseDTO.builder()
        .ticketId(savedTicket.getId())
        .ticketType(req.getTicketType())
        .ticketStatus(savedTicket.getStatus())
        .registeredAt(savedTicket.getRegisterAt())
        .expireAt(savedTicket.getExpireAt())
        .customerId(customer.getId())
        .customerName(customer.getUsername())
        .vehicleId(savedVehicle.getId())
        .plateNumber(savedVehicle.getPlateNumber())
        .profileId(profile.getId())
        .amount(amount)
        .paymentStatus(paymentStatus)
        .invoiceNo(payment != null ? payment.getInvoiceNo() : null)
        .qrCode(qrCode)
        .paymentLink(paymentLink)
        .build();
}

private ParkingCustomerEntity getOrCreateCustomer(
        String name,
        String phone,
        String zaloId) {
    
    // Try find by phone
    Optional<ParkingCustomerEntity> existing = parkingCustomerRepository
        .findByPhoneNumber(phone);
    
    if (existing.isPresent()) {
        return existing.get();
    }
    
    // Create new
    ParkingCustomerEntity customer = new ParkingCustomerEntity();
    customer.setUsername(name);
    customer.setPhoneNumber(phone);
    customer.setZaloId(zaloId);
    return parkingCustomerRepository.save(customer);
}

private PaymentEntity createPayment(int amount, String method, String status) {
    PaymentEntity payment = new PaymentEntity();
    payment.setInvoiceNo(generateInvoiceNo());
    payment.setAmount(amount);
    payment.setCurrency("VND");
    payment.setPaymentMethod(method);
    payment.setStatus(status);
    return payment;
}

private LocalDateTime calculateExpireDate(String ticketType) {
    if ("MONTH".equals(ticketType)) {
        return LocalDateTime.now().plusMonths(1);
    } else if ("QUARTER".equals(ticketType)) {
        return LocalDateTime.now().plusMonths(3);
    }
    return LocalDateTime.now().plusMonths(1);
}
```


---

## 3. API Endpoints Summary

| Endpoint | Method | Purpose | Input | Output |
|----------|--------|---------|-------|--------|
| `/api/admin/monthly-ticket-registration` | POST | Admin đăng ký vé tháng cho khách | `AdminMonthlyTicketRegistrationRequest` + image | `TicketRegistrationResponseDTO` |
| `/api/admin/monthly-ticket-registration/{ticketId}` | GET | Lấy chi tiết vé đã tạo | ticketId | `TicketDetailDTO` |
| `/api/admin/monthly-ticket-registration/history` | GET | Lịch sử đăng ký của admin | pagination params | `Page<TicketRegistrationHistoryDTO>` |

---

## 4. DTO Definitions

### 4.1 Request DTO

```java
@Data
public class AdminMonthlyTicketRegistrationRequest {
    @NotBlank(message = "Customer name required")
    private String customerName;
    
    @NotBlank(message = "Phone number required")
    @Pattern(regexp = "^0\\d{9}$", message = "Invalid phone format")
    private String customerPhone;
    
    @Email(message = "Invalid email")
    private String customerEmail;
    
    private String customerZaloId;
    
    @NotBlank(message = "Ticket type required")
    @Pattern(regexp = "^(MONTH|QUARTER)$")
    private String ticketType;
    
    @NotBlank(message = "Plate number required")
    private String vehiclePlateNumber;
    
    @NotBlank(message = "Vehicle type required")
    private String vehicleType;  // "SEDAN", "SUV", "TRUCK", etc
    
    @NotBlank(message = "Plate type required")
    private String plateType;     // "NORMAL", "DIPLOMATIC", "OFFICIAL"
    
    @NotBlank(message = "Payment method required")
    @Pattern(regexp = "^(CASH|QR|BANK_TRANSFER)$")
    private String paymentMethod;
    
    private String notes;         // Ghi chú của admin
    
    // faceImage will be passed as multipart parameter
}
```

### 4.2 Response DTO

```java
@Data
@Builder
public class TicketRegistrationResponseDTO {
    private Long ticketId;
    private String registrationId;
    private String ticketType;      // "MONTH" or "QUARTER"
    private String ticketStatus;    // "ACTIVE" or "INACTIVE"
    
    private Long customerId;
    private String customerName;
    private String customerPhone;
    
    private Long vehicleId;
    private String plateNumber;
    private String vehicleType;
    
    private Integer profileId;      // AI profile ID
    
    private Integer amount;         // Giá tiền (VND)
    private String currency;        // "VND"
    
    private String paymentMethod;
    private String paymentStatus;   // "SUCCESS", "PENDING", "NONE"
    private String invoiceNo;
    
    private String qrCode;          // Base64 QR image (if payment method is QR)
    private String paymentLink;     // MegaPay payment link (if payment method is QR)
    
    private LocalDateTime registeredAt;
    private LocalDateTime expireAt;
    
    private String message;
}
```

### 4.3 Other DTOs

```java
// Detail view for existing ticket
@Data
public class TicketDetailDTO {
    private Long ticketId;
    private String ticketType;
    private String status;
    private LocalDateTime registerAt;
    private LocalDateTime expireAt;
    private Long vehicleId;
    private String plateNumber;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private Integer profileId;
    private Integer amount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// History view
@Data
public class TicketRegistrationHistoryDTO {
    private Long ticketId;
    private String customerName;
    private String plateNumber;
    private String ticketType;
    private LocalDateTime registeredAt;
    private LocalDateTime expireAt;
    private String status;
    private Long registeredByAdminId;
    private String registeredByAdminName;
}
```

---

## 5. Service Classes Structure

```
TicketRegistrationService
├── registerMonthlyTicket(request, faceImage) 
│   ├─ validateRequest()
│   ├─ getOrCreateCustomer()
│   ├─ registerFaceViaAI()
│   ├─ registerPlateViaAI()
│   ├─ createVehicle()
│   ├─ createTicket()
│   ├─ handlePayment()
│   └─ return TicketRegistrationResponseDTO
├── getTicketDetail(ticketId) → TicketDetailDTO
├── getRegistrationHistory(adminId, pagination) → Page<TicketRegistrationHistoryDTO>
├── calculateTicketPrice(ticketType) → int
├── calculateExpireDate(ticketType) → LocalDateTime
└── validateRequest(request) → void

PaymentService (no change needed - existing method handles callback)
```

---

## 6. New Entity - TicketRegistrationSessionEntity (Optional)

Since we're doing everything in one API call, we can either:

**Option A: Keep simple - just track in Ticket + Payment**
- Store registration info directly in TicketEntity
- Track payment via PaymentEntity
- No separate session table

**Option B: Keep for audit trail**
- Create `ticket_registration_session` table
- Log admin who created, timestamp, all steps
- Good for compliance & debugging

Recommendation: **Option B** for better tracking

```sql
CREATE TABLE ticket_registration_session (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL UNIQUE,
    admin_id BIGINT NOT NULL,
    registration_type VARCHAR(50),        -- "MONTHLY", "QUARTERLY"
    customer_name VARCHAR(255),
    vehicle_plate VARCHAR(20),
    payment_method VARCHAR(50),           -- "CASH", "QR", "BANK_TRANSFER"
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    FOREIGN KEY (admin_id) REFERENCES [user_table](id)
);
```

---

## 7. Controller Implementation

```java
@RestController
@RequestMapping("/api/admin/monthly-ticket-registration")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")  // ✅ Admin only
public class AdminTicketRegistrationController {
    
    private final TicketRegistrationService ticketRegistrationService;
    private static final Logger logger = LoggerFactory.getLogger(AdminTicketRegistrationController.class);
    
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TicketRegistrationResponseDTO> registerMonthlyTicket(
            @Valid @RequestPart("data") AdminMonthlyTicketRegistrationRequest request,
            @RequestPart("faceImage") MultipartFile faceImage) {
        
        logger.info("Admin registering monthly ticket - customer: {}, plate: {}", 
                    request.getCustomerName(), request.getVehiclePlateNumber());
        
        try {
            TicketRegistrationResponseDTO response = ticketRegistrationService
                .registerMonthlyTicket(request, faceImage);
            
            logger.info("Monthly ticket registered successfully - ticketId: {}", 
                        response.getTicketId());
            
            return new ApiResponse<>(
                "SUCCESS",
                "Monthly ticket registered successfully",
                response
            );
        } catch (Exception ex) {
            logger.error("Error registering monthly ticket: {}", ex.getMessage(), ex);
            throw ex;
        }
    }
    
    @GetMapping("/{ticketId}")
    public ApiResponse<TicketDetailDTO> getTicketDetail(@PathVariable Long ticketId) {
        return new ApiResponse<>(
            "SUCCESS",
            "Ticket details fetched",
            ticketRegistrationService.getTicketDetail(ticketId)
        );
    }
    
    @GetMapping("/history")
    public ApiResponse<Page<TicketRegistrationHistoryDTO>> getRegistrationHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return new ApiResponse<>(
            "SUCCESS",
            "Registration history fetched",
            ticketRegistrationService.getRegistrationHistory(pageable)
        );
    }
}
```

---

## 8. State Flow - Simplified

```
┌────────────────────────────────────────────────┐
│  Admin Fills Form with All Information         │
│  - Customer name/phone/zalo                    │
│  - Ticket type (MONTH/QUARTER)                 │
│  - Vehicle plate, type                         │
│  - Face image                                  │
│  - Payment method (CASH/QR/BANK_TRANSFER)     │
└─────────────────┬────────────────────────────┘
                  │ Submit POST /register
                  ↓
┌────────────────────────────────────────────────┐
│  Backend Processing (Transactional)            │
│  ├─ Create/Get Customer                        │
│  ├─ Register Face (AI)                         │
│  ├─ Create Vehicle                             │
│  ├─ Register Plate (AI)                        │
│  ├─ Create Ticket (status: ACTIVE/INACTIVE)   │
│  └─ Handle Payment (CASH/QR/BANK_TRANSFER)   │
└─────────────────┬────────────────────────────┘
                  │
        ┌─────────┴──────────┐
        │                    │
        ↓                    ↓
┌──────────────────┐  ┌──────────────────┐
│ CASH Payment     │  │ QR Payment       │
│ Status: ACTIVE   │  │ Status: INACTIVE │
│ Ticket ready     │  │ Waiting callback │
│ Response: 200    │  │ Response: 200    │
└──────────────────┘  │ + QR code        │
                      │ (MegaPay)        │
                      │                  │
                      ├─ User scans QR   │
                      │ Pays via MegaPay │
                      │                  │
                      └─ Callback set    │
                        ticket ACTIVE    │
                        (async)          │
```

---

## 9. Sequence Diagram

```
Admin User                  API              Service Layer           External Service     Database
   │                         │                   │                         │                 │
   ├─ POST /register ────────→                   │                         │                 │
   │ (multipart: data+image)  │                   │                         │                 │
   │                          ├─ Validate ──────→│                         │                 │
   │                          │                  ├─ Check customer ───────────────────────→│
   │                          │                  │←─ customer ──────────────────────────────│
   │                          │                  │                         │                 │
   │                          │                  ├─ Upload image to AI ───→│                 │
   │                          │                  │←─ profileId ────────────│                 │
   │                          │                  │                         │                 │
   │                          │                  ├─ Create Vehicle ──────→│                 │
   │                          │                  │←─ vehicleId ───────────│                 │
   │                          │                  │                         │                 │
   │                          │                  ├─ Register plate to AI ─→                 │
   │                          │                  │←─ plateId ──────────────│                 │
   │                          │                  │                         │                 │
   │                          │                  ├─ Create Ticket ───────→│                 │
   │                          │                  │←─ ticketId ────────────│                 │
   │                          │                  │                         │                 │
   │                          │ If CASH payment:
   │                          │                  ├─ Create Payment ──────→│                 │
   │                          │                  │←─ paymentId ───────────│                 │
   │                          │                  │                         │                 │
   │                          ├─ Response ──────────────────────────────→│
   │←─ 200 OK + Ticket ──────────────────────────────────────────────────│
   │   (status: ACTIVE)       │                  │                         │                 │
   │                          │                  │                         │                 │
   │   OR If QR payment:      │                  │                         │                 │
   │                          │                  ├─ Call MegaPay ────────→│                 │
   │                          │                  │←─ paymentLink+QR ──────│                 │
   │                          ├─ Response ──────────────────────────────→│
   │←─ 200 OK + QR code ─────────────────────────────────────────────────│
   │   (status: INACTIVE)     │                  │                         │                 │
   │                          │                  │                         │                 │
   ├─ Show QR to customer ─────────────────────────────────────────────→│ (customer scans)
   │                          │                  │                         │                 │
   │                          │                  │                         ├─ User pays via  │
   │                          │                  │                         │ MegaPay        │
   │                          │                  │                         │                 │
   │                          │                  │  MegaPay Callback IPN  │
   │                          │←─ Webhook (async) ←─────────────────────│
   │                          │                  │                         │                 │
   │                          │                  ├─ Update Payment ──────→│                 │
   │                          │                  ├─ Update Ticket ───────→│                 │
   │                          │                  │   (status: ACTIVE)     │                 │
   │                          │                  │←─ Success ──────── ────│                 │
   │                          │                  │                         │                 │
   │ Poll /ticket/{id}        │                  │                         │                 │
   ├─ GET /ticket/{id} ──────→│                  │                         │                 │
   │                          ├─ Fetch ────────→│  Ticket is now ACTIVE   │                 │
   │                          ├──────────────────│←─ ticket info ┤         │
   │←─ 200 + Ticket ──────────────────────────────────────────────────────│
   │   (status: ACTIVE)       │                  │                         │                 │
```

---

## 10. Error Handling Strategy

```java
// Input Validation Errors (400)
- Invalid ticketType → Bad Request
- Invalid phone format → Bad Request
- Missing required field → Bad Request
- Unsupported payment method → Bad Request
- Image file too large or invalid format → Bad Request

// Business Logic Errors (400/409)
- Customer/phone already registered (for plate) → Conflict
- Plate already registered → Conflict
- Vehicle/plate duplicate → Conflict

// External Service Errors (500/503)
- AI profile creation failed → Internal Server Error
- AI plate registration failed → Internal Server Error
- MegaPay API failed → Service Unavailable
- Payment service timeout → Service Unavailable

// Database Errors (500)
- Customer save failed → Internal Server Error
- Vehicle save failed → Internal Server Error
- Ticket save failed → Internal Server Error
- Payment save failed → Internal Server Error

// Generic Handler
- Log all errors with context (customer name, plate, etc)
- Return: { "status": "ERROR", "message": "...", "code": "..." }
```

---

## 11. Validation Rules

```
customerName:
  ├─ Required
  ├─ Min 3 characters
  └─ Max 255 characters

customerPhone:
  ├─ Required
  ├─ Format: ^0\d{9}$ (Vietnamese: 0987654321)
  └─ Unique (except: can be duplicate if no active vehicle)

vehiclePlateNumber:
  ├─ Required
  ├─ Format: ^[0-9]{2}[A-Z]{1}[0-9]{4,5}$ (29A12345)
  └─ Unique

ticketType:
  ├─ Required
  └─ Enum: MONTH, QUARTER

paymentMethod:
  ├─ Required
  └─ Enum: CASH, QR, BANK_TRANSFER

faceImage:
  ├─ Required
  ├─ Format: JPEG, PNG
  └─ Max size: 10MB
```

---

## 12. Ticket Pricing

| Type | Duration | Price | Notes |
|------|----------|-------|-------|
| MONTH | 1 month (30 days) | 1,500,000 VND | Auto-renew reminder at day 25 |
| QUARTER | 3 months (90 days) | 4,000,000 VND | Auto-renew reminder at day 80 |

*Note: Config these values in `application.yml`:*
```yaml
ticket:
  pricing:
    monthly: 1500000    # VND
    quarterly: 4000000  # VND
```

---

## 13. Implementation Checklist

- [ ] Create `AdminMonthlyTicketRegistrationRequest` DTO
- [ ] Create `TicketRegistrationResponseDTO` DTO
- [ ] Create `TicketDetailDTO` and `TicketRegistrationHistoryDTO` DTOs
- [ ] Create `TicketRegistrationService` with main method
- [ ] Create `AdminTicketRegistrationController`
- [ ] Add `@PreAuthorize("hasRole('ADMIN')")` to controller
- [ ] Create `TicketRegistrationSessionEntity` (optional but recommended)
- [ ] Add validation annotations to request DTO
- [ ] Add error handling with proper HTTP status codes
- [ ] Add logging at key points
- [ ] Test with multipart form-data payload
- [ ] Test with CASH, QR, and BANK_TRANSFER payment methods
- [ ] Test rollback on external service failure
- [ ] Add audit trail (who created, when, what)
- [ ] Add unit tests for validation
- [ ] Add integration tests for full flow

---

## 14. Migration SQL (Optional Session Table)

```sql
CREATE TABLE ticket_registration_session (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL UNIQUE,
    admin_id BIGINT,
    customer_name VARCHAR(255) NOT NULL,
    customer_phone VARCHAR(20),
    vehicle_plate VARCHAR(20),
    ticket_type VARCHAR(20),
    payment_method VARCHAR(50),
    registration_status VARCHAR(50),  -- "SUCCESS", "PENDING", "FAILED"
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (ticket_id) REFERENCES ticket(id)
);

CREATE INDEX idx_ticket_id ON ticket_registration_session(ticket_id);
CREATE INDEX idx_admin_id ON ticket_registration_session(admin_id);
CREATE INDEX idx_created_at ON ticket_registration_session(created_at DESC);
```

---

**Document Version:** 2.0 (Admin-Specific Edition)  
**Created:** May 13, 2026  
**Updated:** May 13, 2026 (Admin flow)
