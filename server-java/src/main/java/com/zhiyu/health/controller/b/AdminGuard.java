package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.entity.StaffUser;
import jakarta.servlet.http.HttpServletRequest;

/** B 端组织管理统一仅 admin 可操作（对齐票 02 契约），doctor 角色 403 */
final class AdminGuard {

    private AdminGuard() {
    }

    static void requireAdmin(HttpServletRequest request) {
        if (!StaffUser.ROLE_ADMIN.equals(request.getAttribute(AuthFilter.ATTR_AUTH_ROLE))) {
            throw new ApiException(403, "仅管理员可操作");
        }
    }
}
