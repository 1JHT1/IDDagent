package com.IDDagent.config;
/*
* Spring WebFlux 的 CORS（跨域资源共享）配置类，用于全局设置响应式应用（如 WebClient 或基于 Netty 的 WebFlux 服务）的跨域访问规则。
* */
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;


@Configuration
public class CorsConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
