package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.util.List;

@Entity
@Table(name = "building")
@Data
@EqualsAndHashCode(callSuper = true)
public class BuildingEntity extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;
    // Quan hệ 1-n với ParkingLot
    @OneToMany(mappedBy = "building", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ParkingLotEntity> parkingLots;
}