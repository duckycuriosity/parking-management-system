package vio.barie.core.entity;

import cn.hutool.json.JSONObject;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import vio.barie.core.entity.base.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "payment")
@Data
@EqualsAndHashCode(callSuper = true)
public class PaymentEntity extends BaseEntity {

    @Column(name = "session_id")
    private Long sessionId;
    // thông tin tk ngân hàng snapshot (dạng chụp)
    @Column(name = "beneficiary_account_id")
    private Long beneficiaryAccountId;

    @Column(name = "bank_name", length = 255)
    private String bankName;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "account_holder_name", length = 255)
    private String accountHolderName;

    // === Trường nghiệp vụ bãi xe ===
    @Column(name = "invoice_no", length = 100)
    private String invoiceNo;

    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "payment_method", length = 10)
    private String paymentMethod; // CASH, QR

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "status", length = 20)
    private String status; // PENDING, PAID, FAILED, REFUNDED, FRAUD_SUSPECTED

    @Column(name = "result_code", length = 20)
    private String resultCode;

    @Column(name = "result_message", length = 500)
    private String resultMessage;

    @Column(name = "pay_type", length = 10)
    private String payType; // IC, DC, VA, EW, QR, PL, CW...

    @Column(name = "description", length = 255)
    private String description;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Loại tác vụ sau thanh toán: SESSION, TICKET, ...
    @Column(name = "callback_type")
    private String callbackType;

    // Hướng dẫn thao tác khi payment hoàn thành, lưu dạng JSONB
    // SESSION: {"sessionId": 123, "barrierId": "barrier_001", "plateNumber": "51A-12345"}
    // TICKET:  {"ticketId": 456, "customerId": 789}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "callback_context", columnDefinition = "jsonb")
    private JSONObject callbackContext;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_customer_id", unique = true, referencedColumnName = "id")
    private ParkingCustomerEntity parkingCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sys_user_id", referencedColumnName = "id")
    private SysUserEntity sysUser;

    /** them truong cho SePay */
    @Column(name = "sepay_tx_id", unique = true)
    private String sepayTxId;
    @Column(name = "gateway", length = 50)
    private String gateway;
    @Column(name = "reference_code", length = 100)
    private String referenceCode;
}