package vio.barie.core.dto.parking;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageDetail {
    private String name;
    private String timestamp;
    private String type;
}