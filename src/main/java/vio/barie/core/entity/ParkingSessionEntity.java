package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

import vio.barie.core.common.TicketType;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "parking_session")
@Data
@EqualsAndHashCode(callSuper = true)
public class ParkingSessionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_lot_id", nullable = false)
    private ParkingLotEntity parkingLot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private VehicleEntity vehicle;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_customer_id")
    private ParkingCustomerEntity entryCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exit_customer_id")
    private ParkingCustomerEntity exitCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_lane_id")
    private LaneEntity entryLane;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exit_lane_id")
    private LaneEntity exitLane;

    @Column(name = "entry_barrier")
    private String entryBarrier;

    @Column(name = "exit_barrier")
    private String exitBarrier;

    @Column(name = "ticket_id")
    private Long ticketId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false)
    private TicketType sessionType; // 'MONTHLY', 'QUARTERLY', 'YEARLY', 'DAILY'
}
