package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.math.BigDecimal;

@Entity
@Table(name = "pricing_rule")
@Data
@EqualsAndHashCode(callSuper = true)
public class PricingRuleEntity extends BaseEntity {

    @Column(name = "type", unique = true)
    private String type; // 'DAY', 'MONTH'

    @Column(name = "name")
    private String name;

    @Column(name = "price")
    private int price;

    @Column(name = "currency")
    private String currency;
}