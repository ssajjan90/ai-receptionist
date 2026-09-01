package com.aireceptionist.admin.web;

import com.aireceptionist.admin.repository.AdminAccessLogRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAccessLogRepository adminAccessLogRepository;

    public AdminWebConfig(AdminAccessLogRepository adminAccessLogRepository) {
        this.adminAccessLogRepository = adminAccessLogRepository;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAccessAuditInterceptor(adminAccessLogRepository))
                .addPathPatterns("/v1/admin/**");
    }
}
