package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import vio.barie.core.common.HealthStatus;
import vio.barie.core.health.InternetHealthEventPublisher;
import vio.barie.core.health.InternetHealthState;
import vio.barie.core.health.SystemHealthStateStore;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
@Slf4j
@Service
@RequiredArgsConstructor
public class InternetHealthCheckSchedulerService {
    private final SystemHealthStateStore stateStore;
    private final InternetHealthEventPublisher eventPublisher;

    @Value("${internet.check.disconnect-threshold:3}")
    private int disconnectThreshold;

    private final List<String> targets = List.of(
            "http://clients3.google.com/generate_204",
            "http://1.1.1.1",
            "http://www.google.com"
    );

    private final RestTemplate restTemplate = createRestTemplate();
    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        return new RestTemplate(factory);
    }
    @Scheduled(fixedRateString = "${internet.check.interval-ms:15000}")
    public void performHealthCheck() {
        applyJitter();

        InternetHealthState state = stateStore.getInternetState();
        state.updateLastCheckTime();
        log.info("Check state của internet:{}",state);
        boolean isConnected = checkInternetConnectivity();

        if (isConnected) {
            handleSuccess(state);
        } else {
            handleFailure(state);
        }
    }
    private boolean checkInternetConnectivity() {
        for (String target : targets) {
            try {
                restTemplate.getForEntity(target, String.class);
                log.info("Check internet connect {}", target);
                return true;
            } catch (Exception e) {
                log.debug("Ping to {} failed: {}", target, e.getMessage());
            }
        }
        return false;
    }
    private void handleSuccess(InternetHealthState state) {

        boolean justRecorvered  = state.recordSuccess();

        if (justRecorvered){
            eventPublisher.publishStatusChange(
                    HealthStatus.DISCONNECTED,
                    HealthStatus.CONNECTED,
                    "CONNECTED",
                    state.getLastDowntimeSeconds()
            );
        }

    }
    private void handleFailure(InternetHealthState state) {

        boolean justDisconnected = state.recordFailure(disconnectThreshold);
        log.info("Mạng có vẻ lỗi... Số lần tịt liên tiếp: {}/{}", state.getConsecutiveFailures(), disconnectThreshold);
        if(justDisconnected){
            log.info("Internet check bị fail, đã rớt mạng: {}", state);
            eventPublisher.publishStatusChange(
                    HealthStatus.CONNECTED,
                    HealthStatus.DISCONNECTED,
                    "TIME_OUT",
                    null
            );
        }
    }
    private void applyJitter() {
        try {
            long jitter = ThreadLocalRandom.current().nextLong(2000);
            Thread.sleep(jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
