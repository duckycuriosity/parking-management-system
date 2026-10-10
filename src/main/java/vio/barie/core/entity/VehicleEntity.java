package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "vehicle")
@Data
@EqualsAndHashCode(callSuper = true)
public class VehicleEntity extends BaseEntity {

    @Column(name = "plate_number", unique = true, nullable = false)
    private String plateNumber;

    @Column(name = "status")
    private String status;

    @Column(name = "ai_identity_plate_id")
    private String aiIdentityPlateId;

    @Column(name = "vehicle_type", nullable = false)
    private String vehicleType;

    @Column(name = "owner_id")
    private Long ownerId;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "info", columnDefinition = "jsonb")
    private String info; // Hoặc Object nếu bạn muốn map tự động
}
