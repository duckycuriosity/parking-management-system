package vio.barie.core.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class ParkingFeeTransactionKeyId implements Serializable {
    private String source;
    private String reference;
}
