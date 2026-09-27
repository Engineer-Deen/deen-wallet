package com.glr.deenwallet.config;
import org.springframework.context.annotation.Configuration; import org.springframework.web.servlet.config.annotation.*;
@Configuration public class GlobalCorsConfig implements WebMvcConfigurer {
    @Override public void addCorsMappings(CorsRegistry r){}
}

