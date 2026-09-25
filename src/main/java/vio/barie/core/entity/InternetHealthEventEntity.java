package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
@Entity
@Table(name = "internet_health_events", indexes = {
        @Index(name = "idx_changed_at", columnList = "changed_at DESC")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternetHealthEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "previous_status", nullable = false, length = 20)
    private String previousStatus;

    @Column(name = "current_status", nullable = false, length = 20)
    private String currentStatus;

    @Column(name = "reason", length = 100)
    private String reason;

    @Column(name = "downtime_seconds")
    private Long downtimeSeconds;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
