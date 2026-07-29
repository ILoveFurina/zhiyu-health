package com.zhiyu.health.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 装配：三个过滤器按 order 10（审计）/20（鉴权）/30（限流）注册，CORS 白名单走环境变量 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String jwtSecret;
    private final int permitsPerMinute;
    private final String[] corsOrigins;
    private final String agentCallbackSecret;

    public WebConfig(
            // 演示占位默认值，生产一律走 .env 注入
            @Value("${zhiyu.jwt-secret:zhiyu-dev-only-placeholder-secret}") String jwtSecret,
            @Value("${zhiyu.agent.callback-secret}") String agentCallbackSecret,
            @Value("${zhiyu.rate-limit.permits-per-minute:60}") int permitsPerMinute,
            @Value("${CORS_ORIGINS:http://localhost:5173}") String corsOrigins) {
        this.jwtSecret = jwtSecret;
        this.agentCallbackSecret = agentCallbackSecret;
        this.permitsPerMinute = permitsPerMinute;
        this.corsOrigins = Arrays.stream(corsOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Bean
    public FilterRegistrationBean<AuditFilter> auditFilterRegistration() {
        FilterRegistrationBean<AuditFilter> bean = new FilterRegistrationBean<>(new AuditFilter());
        bean.addUrlPatterns("/api/*");
        bean.setOrder(10);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
        FilterRegistrationBean<RateLimitFilter> bean =
                new FilterRegistrationBean<>(new RateLimitFilter(permitsPerMinute));
        bean.addUrlPatterns("/api/*");
        bean.setOrder(30);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilterRegistration() {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(jwtSecret));
        bean.addUrlPatterns("/api/*");
        bean.setOrder(20);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<AgentCallbackAuthFilter> agentCallbackAuthFilterRegistration() {
        FilterRegistrationBean<AgentCallbackAuthFilter> bean =
                new FilterRegistrationBean<>(new AgentCallbackAuthFilter(agentCallbackSecret));
        bean.addUrlPatterns("/api/agent/*");
        bean.setOrder(20);
        return bean;
    }

    /**
     * B 端默认仅 admin 可操作；放行员工认证区（登录/资料，任何已登录员工可用）
     * 与接诊台（医生工作台，doctor 角色由 ReceptionService 在业务层校验）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminInterceptor())
                .addPathPatterns("/api/b/**")
                .excludePathPatterns("/api/b/auth/**", "/api/b/reception/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsOrigins)
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
