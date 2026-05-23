package vio.barie.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketCreateRequest {

    private Long vehicleId;
    private Long customerId;
    private String ticketType;
    // FE truyền lên số Long (mili-giây), Backend tự động dịch sang LocalDateTime
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private LocalDateTime registerAt;
    // FE truyền lên số Long (mili-giây), Backend tự động dịch sang LocalDateTime
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private LocalDateTime expireAt;
    private String status;
}
