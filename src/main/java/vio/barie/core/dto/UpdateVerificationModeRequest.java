package vio.barie.core.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import vio.barie.core.common.VerificationMode;

@Data
public class UpdateVerificationModeRequest {

    @NotNull(message = "verificationMode is required")
    private VerificationMode verificationMode;
}
