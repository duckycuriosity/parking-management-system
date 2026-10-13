package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.common.BaseStatus;
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

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private BaseStatus status;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "building_code", unique = true)
    private String buildingCode;

    @Column(name = "management_unit")
    private String managementUnit;

    // Quan hệ 1-n với ParkingLot
    @OneToMany(mappedBy = "building", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ParkingLotEntity> parkingLots;
}