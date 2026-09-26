package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.dto.parking.EventImageInfo;
import vio.barie.core.dto.parking.ImageDetail;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LaneEventImage {
    private String role;
    private String full;
    private String crop;

    /** One entry per "face"/"plate" capture found in infos, role = rolePrefix + eventType (e.g. entry_face). */
    public static List<LaneEventImage> from(String rolePrefix, List<EventImageInfo> infos) {
        List<LaneEventImage> result = new ArrayList<>();
        if (infos == null) {
            return result;
        }
        for (EventImageInfo info : infos) {
            if (info == null || info.getImageInfo() == null || info.getEventType() == null) {
                continue;
            }
            String full = null;
            String crop = null;
            for (ImageDetail detail : info.getImageInfo()) {
                if ("root".equals(detail.getType())) {
                    full = detail.getName();
                } else if ("crop".equals(detail.getType())) {
                    crop = detail.getName();
                }
            }
            if (full != null || crop != null) {
                result.add(new LaneEventImage(rolePrefix + info.getEventType(), full, crop));
            }
        }
        return result;
    }
}
