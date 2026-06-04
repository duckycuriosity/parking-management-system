package vio.barie.core.dto;

import lombok.Builder;
import lombok.Data;
import vio.barie.core.common.BarrierActionType;

@Data
@Builder
public class LockCmdCommandResponse {
    private String barrierId;
    private BarrierActionType action;
}
