package vio.barie.core.dto.health;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
@Builder
@Getter
@ToString
public class InternetHealthEvent {
    private String event;
    private String previousStatus;
    private String status;
    private String reason;
    private String changedAt;
    private Long downtimeSeconds;
}
