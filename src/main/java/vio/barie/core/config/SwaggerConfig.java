package vio.barie.core.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;

import java.util.List;

@Configuration
@OpenAPIDefinition
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Barie Core Service API")
                        .version("1.0.0")
                        .description("API documentation for Barie Core Service - Parking Management System")
                        .contact(new Contact()
                                .name("Barie Team")
                                .email("support@barie.com")
                                .url("https://barie.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")))
//                .servers(List.of(
//                        new Server().url("http://localhost:8080").description("Local Server"),
//                        new Server().url("https://api.barie.com").description("Production Server")
//                ))
                .components(new Components());
    }
}
