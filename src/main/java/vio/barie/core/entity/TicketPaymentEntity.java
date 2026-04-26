package vio.barie.core.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "ticket_payment_history")
@Data
@EqualsAndHashCode(callSuper = true)
public class TicketPaymentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false, referencedColumnName = "id")
    private TicketEntity ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false, referencedColumnName = "id")
    private PaymentEntity payment;

    @Column(name = "notes")
    private String notes;
}