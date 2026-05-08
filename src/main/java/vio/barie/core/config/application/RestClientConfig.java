package vio.barie.core.config.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean("aiRestClient")
    public RestClient getAiRestClient(){
        // config rest client cho ai service
        return RestClient.builder().build();
    }
}
