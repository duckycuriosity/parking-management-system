package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "ticket")
@Data
@EqualsAndHashCode(callSuper = true)
public class TicketEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private DeviceEntity device;

    @Column(name = "register_at", nullable = false)
    private LocalDateTime registerAt;

    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    @Column(name = "duration")
    private String duration;

    @Column(name = "type")
    private String type; // 'DAY', 'MONTH', 'YEAR'

    @Column(name = "status")
    private String status; // 'ACTIVE', 'DEAD'

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private PaymentEntity payment;

    @Column(name = "payment_method")
    private String paymentMethod; // 'CASH', 'QR'
}