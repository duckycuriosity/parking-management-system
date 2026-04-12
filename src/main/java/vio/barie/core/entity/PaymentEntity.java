package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
@Data
@EqualsAndHashCode(callSuper = true)
public class PaymentEntity extends BaseEntity {

    @Column(name = "invoiceNo")
    private String invoiceNo;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "payment_method")
    private String paymentMethod; // 'CASH', 'QR'

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "status")
    private String status;

    // Quan hệ 1-1 với SysUser
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_customer_id", unique = true, referencedColumnName = "id")
    private ParkingCustomerEntity parkingCustomer;
}