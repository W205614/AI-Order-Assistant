package com.ai.assistant.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册鉴权拦截器。
 * 只拦截业务 API；静态页面（/chat/index.html、/admin/index.html 等）不拦截。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/dish/**", "/order/**", "/chat",
                        "/admin/stats",
                        "/admin/orders", "/admin/orders/**",
                        "/admin/dishes", "/admin/dishes/**")
                .excludePathPatterns("/admin/login");
    }
}
