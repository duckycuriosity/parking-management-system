package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.dto.health.InternetHealthEvent;
import vio.barie.core.entity.InternetHealthEventEntity;
import vio.barie.core.repository.InternetHealthEventRepository;

import java.time.Instant;
@Slf4j
@Service
@RequiredArgsConstructor
public class InternetHealthEventLogService {
    private final InternetHealthEventRepository repository;

    @Transactional
    public void saveEvent(InternetHealthEvent dtoEvent){
        try{
            InternetHealthEventEntity entity = InternetHealthEventEntity.builder()
                    .eventType(dtoEvent.getType())
                    .previousStatus(dtoEvent.getPreviousStatus())
                    .currentStatus(dtoEvent.getStatus())
                    .reason(dtoEvent.getReason())
                    .downtimeSeconds(dtoEvent.getDowntimeSeconds())
                    .changedAt(Instant.parse(dtoEvent.getChangedAt()))
                    .build();
            repository.save(entity);
            log.debug("Đã lưu lịch sử mạng vào DB: {}", entity.getCurrentStatus());
        }catch (Exception e){
            log.error("Lỗi khi lưu lịch sử vào DB");
        }
    }
    @Transactional(readOnly = true)
    public Page<InternetHealthEventEntity> getEvents(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
