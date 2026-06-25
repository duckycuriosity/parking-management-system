package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventImageInfo {
    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("image_info")
    private List<ImageDetail> imageInfo = new ArrayList<>();

   
}

