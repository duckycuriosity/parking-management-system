package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.util.List;

@Entity
@Table(name = "lane")
@Data
@EqualsAndHashCode(callSuper = true)
public class LaneEntity extends BaseEntity {

    @Column(name = "name")
    private String name;

    @Column(name = "type")
    private String type; // 'ENTRY' or 'EXIT'

    @Column(name = "status")
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_lot_id", nullable = false)
    private ParkingLotEntity parkingLot;
    @OneToMany(mappedBy = "lane", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CameraEntity> cameras;

    @OneToMany(mappedBy = "lane", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ParkingDeviceEntity> parkingDevices;
}
