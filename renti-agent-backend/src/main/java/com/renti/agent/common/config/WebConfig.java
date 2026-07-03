package com.renti.agent.common.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：跨域（携带 Cookie）、会话拦截器、内部令牌拦截器与参数解析器注册。
 */
@Configuration
@EnableConfigurationProperties(RentiProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final RentiProperties properties;
    private final SessionAuthInterceptor sessionAuthInterceptor;
    private final InternalTokenInterceptor internalTokenInterceptor;
    private final SessionAuthInterceptor.CurrentUserResolver currentUserResolver;
    private final SessionAuthInterceptor.CurrentAdminResolver currentAdminResolver;

    public WebConfig(RentiProperties properties,
                     SessionAuthInterceptor sessionAuthInterceptor,
                     InternalTokenInterceptor internalTokenInterceptor,
                     SessionAuthInterceptor.CurrentUserResolver currentUserResolver,
                     SessionAuthInterceptor.CurrentAdminResolver currentAdminResolver) {
        this.properties = properties;
        this.sessionAuthInterceptor = sessionAuthInterceptor;
        this.internalTokenInterceptor = internalTokenInterceptor;
        this.currentUserResolver = currentUserResolver;
        this.currentAdminResolver = currentAdminResolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sessionAuthInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(internalTokenInterceptor).addPathPatterns("/internal/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
        resolvers.add(currentAdminResolver);
    }
}
