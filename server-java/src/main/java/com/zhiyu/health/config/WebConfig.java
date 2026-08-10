package com.zhiyu.health.config;

import com.zhiyu.health.entity.common.StaffUser;
import java.util.Arrays;
import java.util.Set;
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

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DemoFreezeGate.class)
    public FilterRegistrationBean<DemoFreezeFilter> demoFreezeFilterRegistration(
            DemoFreezeGate demoFreezeGate, Contracts contracts) {
        // 演示重置冻结：order 25，鉴权 20 之后、限流 30 之前；仅拦 C 端入口。
        // @ConditionalOnBean：@WebMvcTest 切片不扫描普通 @Component，此时本 Bean 不创建，
        // 避免切片上下文缺 DemoFreezeGate 而失败；全量上下文下正常注册。
        FilterRegistrationBean<DemoFreezeFilter> bean =
                new FilterRegistrationBean<>(new DemoFreezeFilter(demoFreezeGate, contracts));
        bean.addUrlPatterns("/api/c/*");
        bean.setOrder(25);
        return bean;
    }

    /**
     * B 端路由级角色矩阵（票 88，ADR-0035）：具体路由先注册、catch-all 兜底 admin-only。
     * - doctor-only：/api/b/reception/**（接诊/排班/开方医生工作台）
     * - admin-or-pharmacist：处方审核、药品订单、院区药房库存、标准药品目录
     *   （目录读/处方属性维护是药师现场补药与库存页的必要依赖）
     * - admin-only：其余 /api/b/**（组织/系统），放行员工认证区（登录/资料，任何已登录员工可用）
     * 角色取值经 StaffUser 常量与 contracts/staff-roles.json 绑定（ContractsTest 断言同源）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new StaffRoleInterceptor(Set.of(StaffUser.ROLE_DOCTOR), "仅医生可操作"))
                .addPathPatterns("/api/b/reception/**");
        registry.addInterceptor(
                        new StaffRoleInterceptor(Set.of(StaffUser.ROLE_ADMIN, StaffUser.ROLE_PHARMACIST), "仅管理员或药师可操作"))
                .addPathPatterns(
                        "/api/b/prescriptions/**",
                        "/api/b/drug-orders/**",
                        "/api/b/campus-pharmacies/**",
                        "/api/b/pharmacy-medications/**",
                        "/api/b/medications/**",
                        "/api/b/campuses/*/pharmacy");
        registry.addInterceptor(new StaffRoleInterceptor(Set.of(StaffUser.ROLE_ADMIN), "仅管理员可操作"))
                .addPathPatterns("/api/b/**")
                .excludePathPatterns(
                        "/api/b/auth/**",
                        "/api/b/reception/**",
                        "/api/b/prescriptions/**",
                        "/api/b/drug-orders/**",
                        "/api/b/campus-pharmacies/**",
                        "/api/b/pharmacy-medications/**",
                        "/api/b/medications/**",
                        "/api/b/campuses/*/pharmacy");
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
