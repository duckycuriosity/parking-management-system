package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
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
    @JoinColumn(name = "device_id", nullable = false)
    private DeviceEntity device;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_lane_id") // Giả sử FK tới lane
    private LaneEntity entryLane;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exit_lane_id") // Giả sử FK tới lane
    private LaneEntity exitLane;
}
