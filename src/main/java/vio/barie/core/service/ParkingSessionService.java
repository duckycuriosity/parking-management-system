package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.repository.ParkingSessionRepository;

import java.util.List;


@Service
@RequiredArgsConstructor
public class ParkingSessionService {

    private final ParkingSessionRepository parkingSessionRepository;

    public List<ActivitySessionResponse> getTop10ActivitiesByLane(Long laneId) {
        Page<ActivitySessionResponse> page = parkingSessionRepository.getActivityHistoryByLaneId(laneId, PageRequest.of(0, 10));
        return page.getContent();
    }

    public Page<ActivitySessionResponse> getActivitiesByLane(Long laneId, Pageable pageable) {
        return parkingSessionRepository.getActivityHistoryByLaneId(laneId, pageable);
    }

}
