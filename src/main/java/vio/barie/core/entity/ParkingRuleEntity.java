package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "parking_rule")
@Data
@EqualsAndHashCode(callSuper = true)
public class ParkingRuleEntity extends BaseEntity {

    @Column(name = "name")
    private String name;
}