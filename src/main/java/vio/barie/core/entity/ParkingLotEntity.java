package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.util.List;

@Entity
@Table(name = "parking_lot")
@Data
@EqualsAndHashCode(callSuper = true)
public class ParkingLotEntity extends BaseEntity {

    @Column(name = "name")
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    private BuildingEntity building;

//    // Quan hệ 1-n với Lane
//    @OneToMany(mappedBy = "parkingLot", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
//    private List<LaneEntity> lanes;
//
//    // Quan hệ 1-n với ParkingSession
//    @OneToMany(mappedBy = "parkingLot", fetch = FetchType.LAZY)
//    private List<ParkingSessionEntity> parkingSessions;
}
