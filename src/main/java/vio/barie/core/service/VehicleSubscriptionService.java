package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.dto.VehicleSubscriptionResponse;
import vio.barie.core.repository.VehicleSubscriptionRepository;

import java.time.LocalDateTime;
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleSubscriptionService {
    private final VehicleSubscriptionRepository vehicleSubscriptionRepository;
    @Transactional(readOnly = true)
    public Page<VehicleSubscriptionResponse> findAll(String keyword, Long buildingId, Long parkingLotId,
                                                     LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return vehicleSubscriptionRepository.findSubscriptionDetails(
                keyword, buildingId, parkingLotId, startDate, endDate, pageable);
    }
}
