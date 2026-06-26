package vio.barie.core.dto;

import lombok.Data;


public interface SessionStatProjection {
    String getSessionType();

    String getVehicleType();

    Long getTotal();

}
