package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.InOutStatus;
import vio.barie.core.common.SessionType;
import vio.barie.core.dto.parking.EventImageInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ParkingSessionPaymentDetailResponse {
    private Long sessionId;
    private String plateNumber;
    private String userName;
    private String entryLaneName;
    private String exitLaneName;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private String parkingDuration;
    private SessionType sessionType;
    private String typeVehicle;
    private String fee;
    private String land;
    private String buildingName;
    private InOutStatus statusInOut;
    private List<EventImageInfo> entryImageInfo;
    private List<EventImageInfo> exitImageInfo;

    private Long paymentId;
    private String invoiceNo;
    private BigDecimal paymentAmount;
    private String paymentAmountText;
    private String currency;
    private String paymentMethod;
    private LocalDateTime paidAt;
    private String paymentStatus;
    private String resultCode;
    private String resultMessage;
    private String payType;
    private String paymentDescription;
    private String gateway;
    private String referenceCode;
    private String sepayTxId;
}
