package vio.barie.core.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "parking_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParkingMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Column(name = "message_type", nullable = false, length = 30)
    private String messageType;

    @Column(name = "topic", length = 255)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "reason", length = 100)
    private String reason;

    @Column(name = "uptime_seconds")
    private Long uptimeSeconds;

    @Column(name = "cpu_temp_c", precision = 10, scale = 2)
    private BigDecimal cpuTempC;

    @Column(name = "free_mem_mb")
    private Integer freeMemMb;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}