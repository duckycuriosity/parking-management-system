package vio.barie.core.health;

import org.springframework.stereotype.Component;

@Component
public class SystemHealthStateStore {
    private final  InternetHealthState internetState = new InternetHealthState();
    public InternetHealthState getInternetState() {
        return internetState;
    }
}
