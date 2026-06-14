package vio.barie.core.dto.parking;

import lombok.Data;

@Data
public class HourlyTrafficResponse {
    private String hour;
    private String date;
    private String dateTime;
    private long entries;
    private long exits;
    private long currentParked;
}
