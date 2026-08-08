package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.entity.AgentCallLog;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.AgentCallLogService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端 Agent 调用日志（票 24）：仅 admin 角色可见。
 *
 * 项目首个角色鉴权接口，YAGNI 不引入注解/切面，controller 内就地检查
 * @RequestAttribute(AuthFilter.ATTR_AUTH_ROLE)；未来 rule-of-three 再提取 @RequireRole。
 * 不存在的 conversation_id 返回空列表（不 404）。
 */
@RestController
@RequestMapping("/api/b/agent-call-logs")
@RequiredArgsConstructor
public class AgentCallLogController {

    private final AgentCallLogService service;

    /** 有 trace 的会话摘要列表（导航用）；可选按患者昵称模糊筛选。 */
    @GetMapping("/conversations")
    public List<AgentCallLogService.ConversationView> conversations(
            @RequestAttribute(AuthFilter.ATTR_AUTH_ROLE) String role,
            @RequestParam(name = "patient", required = false) String patientKeyword) {
        requireAdmin(role);
        return service.listConversations(patientKeyword);
    }

    /** 指定会话的扁平事件列表（按 round_id + seq 还原顺序）。不存在的会话返回空列表。 */
    @GetMapping
    public List<AgentCallLog> list(
            @RequestAttribute(AuthFilter.ATTR_AUTH_ROLE) String role,
            @RequestParam("conversation_id") Long conversationId) {
        requireAdmin(role);
        return service.listByConversation(conversationId);
    }

    private void requireAdmin(String role) {
        if (!StaffUser.ROLE_ADMIN.equals(role)) {
            throw new ApiException(403, "仅管理员可查看 Agent 调用日志");
        }
    }
}
