package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "parking_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParkingDeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", unique = true, nullable = false, length = 100)
    private String deviceId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "has_cash_reader")
    private Boolean hasCashReader;

    @Column(name = "has_display")
    private Boolean hasDisplay;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    @Column(name = "cpu_temp_c", precision = 10, scale = 2)
    private BigDecimal cpuTempC;

    @Column(name = "free_mem_mb")
    private Integer freeMemMb;

    @Column(name = "uptime_seconds")
    private Long uptimeSeconds;

    @Column(name = "disconnect_reason", length = 100)
    private String disconnectReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lane_id")
    private LaneEntity lane;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}