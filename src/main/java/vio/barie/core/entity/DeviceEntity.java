package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vio.barie.core.entity.base.BaseEntity;

import java.util.List;

@Entity
@Table(name = "device")
@Data
@EqualsAndHashCode(callSuper = true)
public class DeviceEntity extends BaseEntity {

    @Column(name = "plate_number", unique = true, nullable = false)
    private String plateNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "info", columnDefinition = "jsonb")
    private String info; // Hoặc Object nếu bạn muốn map tự động
}
