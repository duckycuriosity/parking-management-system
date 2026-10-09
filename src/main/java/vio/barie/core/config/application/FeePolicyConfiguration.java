package vio.barie.core.config.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class FeePolicyConfiguration {

    @Bean("feePolicyClock")
    public Clock feePolicyClock() {
        return Clock.systemUTC();
    }
}
