package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.repository.VmsTokenRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttStartupWarmup {

    private final BarrierRepository barrierRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final TicketRepository ticketRepository;
    private final VehicleRepository vehicleRepository;
    private final VmsTokenRepository vmsTokenRepository;

    public void warmUp() {
        try {
            log.info("Warming up MQTT-related repositories and services before subscription");
            barrierRepository.count();
            parkingCustomerRepository.count();
            parkingSessionRepository.count();
            ticketRepository.count();
            vehicleRepository.count();
            vmsTokenRepository.count();
            log.info("MQTT warm-up completed");
        } catch (Exception ex) {
            log.warn("MQTT warm-up encountered an issue but startup will continue: {}", ex.getMessage(), ex);
        }
    }
}