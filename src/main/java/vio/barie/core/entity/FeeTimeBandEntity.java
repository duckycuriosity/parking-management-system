package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalTime;

/** Local clock window and block prices belonging to exactly one tariff version. */
@Entity
@Table(name = "fee_time_band", indexes = {
        @Index(name = "idx_fee_time_band_policy_id", columnList = "fee_policy_id")
})
@Getter
@Setter
public class FeeTimeBandEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fee_policy_id", nullable = false)
    private FeePolicyEntity feePolicy;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "first_block_minutes", nullable = false)
    private Integer firstBlockMinutes;

    @Column(name = "first_block_price_vnd", nullable = false)
    private Long firstBlockPriceVnd;

    @Column(name = "next_block_minutes", nullable = false)
    private Integer nextBlockMinutes;

    @Column(name = "next_block_price_vnd", nullable = false)
    private Long nextBlockPriceVnd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;
}
