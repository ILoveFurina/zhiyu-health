package com.zhiyu.health.config;

import com.zhiyu.health.entity.StaffUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * B 端组织管理统一仅 admin 可操作（对齐票 02 契约），doctor 角色 403。
 * 依赖 AuthFilter 先行写入的角色 attribute；无角色一律按非管理员拒绝。
 */
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (StaffUser.ROLE_ADMIN.equals(request.getAttribute(AuthFilter.ATTR_AUTH_ROLE))) {
            return true;
        }
        throw new ApiException(403, "仅管理员可操作");
    }
}
