package vio.barie.core.config.auth;

/** Startup setting shared by subscription HTTP authentication and method permissions. */
public class VehicleSubscriptionSecuritySettings {
    private final boolean authEnabled;

    public VehicleSubscriptionSecuritySettings(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }
}
