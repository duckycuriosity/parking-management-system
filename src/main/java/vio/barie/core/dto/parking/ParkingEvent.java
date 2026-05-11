package vio.barie.core.dto.parking;

import lombok.Data;

// AI service gọi vào khi xe đến cổng RA: gửi biển số + id khuôn mặt
@Data
public class ParkingEvent {
    private String plateNumber;
    private Integer aiIdentityPlateId;
    private Integer aiIdentityCustomerId; // id khuôn mặt từ AI
    private Long laneId;
}