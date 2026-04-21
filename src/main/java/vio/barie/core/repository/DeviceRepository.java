package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.DeviceEntity;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<DeviceEntity, Long> {
    Optional<DeviceEntity> findByPlateNumber(String plateNumber);
    Optional<DeviceEntity> findByAiIdentityPlateId(String aiIdentityPlateId);
    //Tìm kiếm thiết bị thông qua ID định danh từ AI (aiIdentityPlateId).
    // Đây có vẻ là ID mà hệ thống AI của bạn trả về sau khi nhận diện hình ảnh.
    boolean existsByPlateNumber (String plateNumber);



}