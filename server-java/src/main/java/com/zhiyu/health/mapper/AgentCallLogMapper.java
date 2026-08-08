package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.AgentCallLog;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentCallLogMapper extends BaseMapper<AgentCallLog> {

    // B 端会话摘要列表：有 trace 的会话，按最近活跃倒序。
    // JOIN conversations 取标题与 last_active_at；JOIN patients 取患者昵称（B 端展示与筛选用）。
    // DISTINCT 去重（每会话多行 trace）。patientKeyword 非空时按患者昵称模糊筛选。
    @Select(
            """
            <script>
            SELECT DISTINCT a.conversation_id, a.patient_id, c.title AS conversation_title,
                   p.nickname AS patient_nickname, c.last_active_at AS last_active_at
            FROM agent_call_logs a
            JOIN conversations c ON c.id = a.conversation_id
            JOIN patients p ON p.id = a.patient_id
            <where>
              <if test="patientKeyword != null and patientKeyword != ''">
                AND p.nickname ILIKE CONCAT('%', #{patientKeyword}, '%')
              </if>
            </where>
            ORDER BY c.last_active_at DESC, a.conversation_id DESC
            </script>
            """)
    List<ConversationTraceSummary> selectConversationSummaries(String patientKeyword);

    // 占位 record：MyBatis 按字段名映射，供 selectConversationSummaries 返回。
    // 实际视图定义在 AgentCallLogService.ConversationView（避免 mapper 层暴露实体外的类型）。
    record ConversationTraceSummary(
            Long conversationId,
            Long patientId,
            String conversationTitle,
            String patientNickname,
            java.time.OffsetDateTime lastActiveAt) {}
}
