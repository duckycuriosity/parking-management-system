package vio.barie.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiRestClientConfig {

    @Bean(name = "aiRestClient")
    public RestClient aiRestClient(
            @Value("${ai.core.base-url}") String baseUrl,
            @Value("${ai.core.user-agent}") String userAgent,
            @Value("${ai.core.accept}") String accept,
            @Value("${ai.core.cookie}") String cookie,
            @Value("${ai.core.connection}") String connection) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();

        ClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", accept)
                .defaultHeader("Cookie", cookie)
                .defaultHeader("Connection", connection)
                .build();
    }
}