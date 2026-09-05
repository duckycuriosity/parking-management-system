package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.camera.AICameraDetail;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.service.AIService.AICameraService;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class CameraSyncJob {
    private final CameraRepository cameraRepository;
    private final AICameraService aiCameraService;
    @Scheduled(fixedDelayString = "${camera.sync.interval-ms:15000}")
    @Transactional
    public void sysCameraStausFormAI(){
        try{
            Map<String, AICameraDetail> aiCameras = aiCameraService.getCameras();
            if(aiCameras == null || aiCameras.isEmpty()){
                log.warn("Không lấy được dữ liệu từ AI, bỏ qua đợt đồng bộ.");
                return;
            }
            List<CameraEntity> dbCameras = cameraRepository.findAll();
            boolean isDbUpdated = false;
            for(CameraEntity entity : dbCameras){
                AICameraDetail detail = aiCameras.get(entity.getAiCameraId());
                BaseStatus newStatus = determineStatus(detail);
                if (entity.getStatus() != newStatus) {
                    log.info("Update Camera ID {}: {} -> {}", entity.getId(), entity.getStatus(), newStatus);
                    entity.setStatus(newStatus);
                    isDbUpdated = true;
                }
            }
            if (isDbUpdated) {
                cameraRepository.saveAll(dbCameras); // Lưu hàng loạt, tránh N+1 Query
            }
        }catch (Exception e){
            log.error("Lỗi trong quá trình đồng bộ trạng thái: {}", e.getMessage());

        }
    }
    private BaseStatus determineStatus(AICameraDetail detail) {
        if (detail == null) return BaseStatus.INACTIVE;
        if ("live".equalsIgnoreCase(detail.getStatus())) return BaseStatus.ACTIVE;
        if ("error".equalsIgnoreCase(detail.getStatus())) return BaseStatus.INACTIVE;
        return BaseStatus.INACTIVE; // Mặc định nếu trạng thái không xác định
    }
}
