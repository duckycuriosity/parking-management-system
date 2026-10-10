package vio.barie.core.config.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One startup setting shared by HTTP authentication and method authorization. */
@Component("feePolicySecurity")
public class FeePolicySecuritySettings {

    private final boolean authEnabled;

    public FeePolicySecuritySettings(@Value("${app.fee-policy.auth-enabled:true}") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }
}
