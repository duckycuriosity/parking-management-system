package vio.barie.core.config.vms;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class VmsRestClientConfig {

       @Bean(name = "vmsRestClient")
       public RestClient vmsRestClient(VmsProperties properties) {
              java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                            .version(java.net.http.HttpClient.Version.HTTP_1_1)
                            .build();

              ClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);

              return RestClient.builder()
                            .baseUrl(properties.getBaseUrl())
                            .requestFactory(factory)
                            .defaultHeader("User-Agent", properties.getUserAgent())
                            .defaultHeader("Accept", properties.getAccept())
                            .defaultHeader("Accept-Language", properties.getAcceptLanguage())
                            .defaultHeader("Cookie", properties.getCookie())
                            .defaultHeader("Connection", properties.getConnection())
                            .build();
       }
}
