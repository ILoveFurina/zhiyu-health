package com.zhiyu.health.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * B 端路由级角色授权（票 88，ADR-0035）：替代原 blanket AdminInterceptor，
 * 按路由显式声明允许的角色集合（admin-only / admin-or-pharmacist / doctor-only）。
 * 角色取值只信 AuthFilter 写入的 attribute（JWT 已验签），缺失一律 403。
 */
public class StaffRoleInterceptor implements HandlerInterceptor {

    private final Set<String> allowedRoles;
    private final String message;

    public StaffRoleInterceptor(Set<String> allowedRoles, String message) {
        this.allowedRoles = Set.copyOf(allowedRoles);
        this.message = message;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object role = request.getAttribute(AuthFilter.ATTR_AUTH_ROLE);
        if (role != null && allowedRoles.contains(role.toString())) {
            return true;
        }
        throw new ApiException(403, message);
    }
}
