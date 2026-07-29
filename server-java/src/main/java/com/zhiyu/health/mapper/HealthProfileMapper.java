package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.HealthTimelineEntry;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HealthProfileMapper extends BaseMapper<HealthProfile> {

    @Select("SELECT * FROM health_profiles WHERE id = #{id} AND patient_id = #{patientId}")
    HealthProfile selectOwned(@Param("id") long id, @Param("patientId") long patientId);

    @Select("SELECT * FROM health_profiles WHERE patient_id = #{patientId} AND active = TRUE")
    HealthProfile selectActive(@Param("patientId") long patientId);

    @Update("UPDATE health_profiles SET active = FALSE, updated_at = now() WHERE patient_id = #{patientId} AND active")
    int clearActive(@Param("patientId") long patientId);

    @Update(
            "UPDATE health_profiles SET active = TRUE, updated_at = now() WHERE id = #{id} AND patient_id = #{patientId}")
    int activate(@Param("id") long id, @Param("patientId") long patientId);

    // 只读 UNION 将跨业务表记录投影成统一时间线；不双写聚合表，并以业务发生时间稳定倒序。
    @Select(
            """
            SELECT * FROM (
                SELECT 'APPOINTMENT' AS type, a.id AS record_id,
                       dep.name || '挂号' AS title,
                       d.name || ' · ' || CASE a.status
                           WHEN 'BOOKED' THEN '已约' WHEN 'CANCELLED' THEN '已取消' ELSE '已接诊' END AS summary,
                       a.created_at AS occurred_at, NULL::VARCHAR AS disclaimer
                FROM appointments a
                JOIN schedules s ON s.id = a.schedule_id
                JOIN doctors d ON d.id = s.doctor_id
                JOIN departments dep ON dep.id = d.department_id
                WHERE a.patient_id = #{patientId} AND a.health_profile_id = #{profileId}
                UNION ALL
                SELECT 'PRESCRIPTION', pr.id, '电子处方',
                       d.name || ' · 已审核通过', COALESCE(pr.reviewed_at, pr.created_at), pr.disclaimer
                FROM prescriptions pr
                JOIN appointments a ON a.id = pr.appointment_id
                JOIN doctors d ON d.id = pr.doctor_id
                WHERE a.patient_id = #{patientId} AND a.health_profile_id = #{profileId}
                  AND pr.status = 'APPROVED'
                UNION ALL
                SELECT 'REPORT_INTERPRETATION', r.id, '报告解读',
                       COALESCE((r.result_json::jsonb ->> 'summary'), '报告解读完成'), r.created_at, r.disclaimer
                FROM report_interpretations r
                WHERE r.patient_id = #{patientId} AND r.health_profile_id = #{profileId}
                  AND r.status = 'SUCCEEDED'
            ) timeline
            ORDER BY occurred_at DESC, record_id DESC
            """)
    List<HealthTimelineEntry> selectTimeline(@Param("patientId") long patientId, @Param("profileId") long profileId);
}
