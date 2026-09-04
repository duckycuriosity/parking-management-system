package vio.barie.core.service.health;

import lombok.Data;
import lombok.Getter;
import lombok.ToString;
import vio.barie.core.common.HealthStatus;

import java.time.Duration;
import java.time.Instant;
@Getter
@ToString
public class InternetHealthState {
    private HealthStatus status;
    private int consecutiveFailures;
    private Instant lastCheckAt;
    private Instant lastSuccessAt;
    private Instant disconnectedSince;
    private Long lastDowntimeSeconds;
    private String lastFailureReason;
    public InternetHealthState() {
        this.status = HealthStatus.CONNECTED; // Mặc định khi khởi động là CONNECTED
        this.consecutiveFailures = 0;
        this.lastCheckAt = Instant.now();
    }

    public synchronized void updateLastCheckTime(){
        this.lastCheckAt = Instant.now();
    }
    // cập nhật khi ping thành công
    //

    public synchronized  boolean recordSuccess(){
        this.consecutiveFailures = 0;
        this.lastSuccessAt = Instant.now();
        this.lastFailureReason = null;

        if(this.status == HealthStatus.DISCONNECTED){
            long downtime = (this.disconnectedSince != null)? Duration.between(this.disconnectedSince, Instant.now()).getSeconds() :0;
            this.status = HealthStatus.CONNECTED;
            this.lastDowntimeSeconds = downtime;
            this.disconnectedSince = null;
            return true;
        }
        return false;
    }

    // cập nhật khi ping thất bại

    public synchronized boolean recordFailure(int disconnectThreshold){
        this.consecutiveFailures ++;
        this.lastFailureReason = "ALL_TARGETS_UNREACHABLE";
        if(this.status == HealthStatus.CONNECTED && this.consecutiveFailures >= disconnectThreshold){
            this.status = HealthStatus.DISCONNECTED;
            this.disconnectedSince = Instant.now();
            return true;
        }
        return false;
    }

}
