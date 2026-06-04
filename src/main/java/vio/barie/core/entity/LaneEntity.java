package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.entity.base.BaseEntity;
import vio.barie.core.utils.DirectionType;

import java.util.List;

@Entity
@Table(name = "lane")
@Data
@EqualsAndHashCode(callSuper = true)
public class LaneEntity extends BaseEntity {

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    private DirectionType type;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    private BaseStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_lot_id", nullable = false)
    private ParkingLotEntity parkingLot;
    @OneToMany(mappedBy = "lane", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<CameraEntity> cameras;

    @OneToMany(mappedBy = "lane", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<ParkingDeviceEntity> parkingDevices;
}
