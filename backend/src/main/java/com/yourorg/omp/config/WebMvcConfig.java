package com.yourorg.omp.config;

import com.yourorg.omp.maintenance.MaintenanceInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置：注册全局拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final MaintenanceInterceptor maintenanceInterceptor;

    public WebMvcConfig(MaintenanceInterceptor maintenanceInterceptor) {
        this.maintenanceInterceptor = maintenanceInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册维护模式拦截器：作用于所有请求，内部按白名单决定是否放行
        registry.addInterceptor(maintenanceInterceptor)
                .addPathPatterns("/**")
                // 静态资源不拦截（Spring Boot 默认 /static/**, /public/** 等也属于 /** 范围）
                .excludePathPatterns("/error", "/favicon.ico");
    }
}
