package com.zhiyu.health.service.demo;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 演示看板聚合（票 25，ADR-0022）：单接口返回严格四类指标，不加分页与时间筛选。
 *
 * "今日"取服务器 {@code CURRENT_DATE}。数据经 server-java 聚合接口实时读取，
 * 不得让浏览器直连数据库或 server-py。看板只消费业务数据（只读聚合），不定义新业务实体。
 *
 * DTO 由 JdbcTemplate 直接组装为 record，无 entity 参与映射，故不引入 MapStruct mapper
 * （AGENTS.md 的 MapStruct 约定针对 entity->DTO 转换，此处无适用点）。
 */
@Service
@RequiredArgsConstructor
public class DemoDashboardService {

    private final JdbcTemplate jdbc;

    /** 聚合四类指标：今日挂号量 / 科室分布 / 号源使用率 / Agent 对话量与工具调用次数。 */
    public DashboardView dashboard() {
        int todayAppointments = count("SELECT count(*) FROM appointments WHERE created_at::date = CURRENT_DATE");

        List<DepartmentShare> departmentDistribution = jdbc.query(
                """
                SELECT dep.name AS department, count(a.*) AS count
                FROM appointments a
                JOIN schedules s ON s.id = a.schedule_id
                JOIN doctors doc ON doc.id = s.doctor_id
                JOIN departments dep ON dep.id = doc.department_id
                WHERE a.created_at::date = CURRENT_DATE
                GROUP BY dep.name
                ORDER BY count DESC, dep.name
                """,
                (rs, rowNum) -> new DepartmentShare(rs.getString("department"), rs.getInt("count")));

        Double usageRatio = jdbc.queryForObject(
                """
                SELECT CASE WHEN sum(total_slots) = 0 THEN 0.0
                            ELSE sum(total_slots - remaining_slots)::float / sum(total_slots) END
                FROM schedules WHERE is_active = TRUE
                """,
                Double.class);
        double slotUsageRate = usageRatio == null ? 0.0 : usageRatio;

        int todayChatRounds = count("SELECT count(*) FROM chat_rounds WHERE started_at::date = CURRENT_DATE");
        int todayToolCalls = count("SELECT count(*) FROM agent_call_logs WHERE created_at::date = CURRENT_DATE");

        return new DashboardView(
                todayAppointments,
                departmentDistribution,
                new SlotUsage(slotUsageRate),
                new AgentActivity(todayChatRounds, todayToolCalls));
    }

    private int count(String sql) {
        Integer result = jdbc.queryForObject(sql, Integer.class);
        return result == null ? 0 : result;
    }

    /** 看板聚合视图（严格四类指标，snake_case 经 Jackson 全局策略序列化）。 */
    public record DashboardView(
            int todayAppointments,
            List<DepartmentShare> departmentDistribution,
            SlotUsage slotUsage,
            AgentActivity agentActivity) {
        public DashboardView {
            departmentDistribution = List.copyOf(departmentDistribution);
        }
    }

    public record DepartmentShare(String department, int count) {}

    public record SlotUsage(double rate) {}

    public record AgentActivity(int chatRounds, int toolCalls) {}
}
