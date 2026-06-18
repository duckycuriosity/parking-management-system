package vio.barie.core.config.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
//import javax.annotation.PostConstruct;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("file:/home/admini/AVCoreV2/faces/")
                .setCacheControl(org.springframework.http.CacheControl
                        .maxAge(7, java.util.concurrent.TimeUnit.DAYS)
                        .cachePublic());

        System.out.println("\n✅ STATIC RESOURCE HANDLER ACTIVE!");
        System.out.println("   Location: /home/admini/AVCoreV2/faces/");
    }
}