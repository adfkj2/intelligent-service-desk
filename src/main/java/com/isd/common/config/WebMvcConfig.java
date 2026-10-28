package com.isd.common.config;

import com.isd.auth.security.PasswordChangeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 层配置。
 * 目前只注册一个强制改密拦截器，且只拦 /api/**：
 * 静态资源与 SPA 页面交给 Security 与 SpaForwardController，避免拦截器误伤 index.html。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PasswordChangeInterceptor passwordChangeInterceptor;

    public WebMvcConfig(PasswordChangeInterceptor passwordChangeInterceptor) {
        this.passwordChangeInterceptor = passwordChangeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(passwordChangeInterceptor).addPathPatterns("/api/**");
    }
}
