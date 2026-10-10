package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import vio.barie.core.common.SubscriptionPackageStatus;
import vio.barie.core.config.exception.BusinessException;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

/** A local weekly time window. End before start crosses midnight; equal means 24 hours. */
@Entity
@Table(name = "subscription_package_schedule")
@Getter
@Setter
public class SubscriptionPackageScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "package_id", nullable = false)
    private SubscriptionPackageEntity subscriptionPackage;

    /** Monday is 1, Tuesday is 2, ..., Sunday is 7. */
    @Column(name = "weekday", nullable = false)
    private Integer weekday;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Long loadedPackageId;

    @PostLoad
    @PostPersist
    private void rememberParent() {
        loadedPackageId = subscriptionPackage == null ? null : subscriptionPackage.getId();
    }

    @PrePersist
    @PreUpdate
    @PreRemove
    private void protectPublishedSchedule() {
        if (subscriptionPackage == null) {
            throw new BusinessException("A schedule must belong to a package", "VALIDATION_ERROR", 400);
        }
        if (loadedPackageId != null && !Objects.equals(loadedPackageId, subscriptionPackage.getId())) {
            throw new BusinessException("A schedule cannot move to another package", "CONFLICT", 409);
        }
        if (subscriptionPackage.getStatus() == SubscriptionPackageStatus.PUBLISHED) {
            throw new BusinessException("Published package schedules cannot be changed", "CONFLICT", 409);
        }
    }
}
