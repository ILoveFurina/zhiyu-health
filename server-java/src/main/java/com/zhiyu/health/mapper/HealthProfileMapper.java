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
    // 服药打卡分支读 CHECKED 记录，summary 只投影药名+剂量+频次的事实信息；
    // 连续天数（streak）是跨记录聚合值，由打卡接口现算返回，不进单行时间线投影（ADR-0018）。
    // 票 56 双来源：处方分支 LEFT JOIN 挂号单/在线问诊两来源（INNER JOIN 会漏在线处方），
    // 患者/档案过滤用 COALESCE；在线问诊分支投影 COMPLETED 单，类型字面量与
    // contracts online-consultation.timeline_types 一致（ContractsConsistencyTest 钉死）。
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
                SELECT 'PRESCRIPTION', pr.id,
                       CASE WHEN pr.online_consultation_id IS NOT NULL THEN '在线问诊处方' ELSE '电子处方' END,
                       d.name || ' · 已审核通过', COALESCE(pr.reviewed_at, pr.created_at), pr.disclaimer
                FROM prescriptions pr
                LEFT JOIN appointments a ON a.id = pr.appointment_id
                LEFT JOIN online_consultations oc ON oc.id = pr.online_consultation_id
                JOIN doctors d ON d.id = pr.doctor_id
                WHERE COALESCE(a.patient_id, oc.patient_id) = #{patientId}
                  AND COALESCE(a.health_profile_id, oc.health_profile_id) = #{profileId}
                  AND pr.status = 'APPROVED'
                UNION ALL
                SELECT 'ONLINE_CONSULTATION', oc.id, '在线问诊',
                       d.name || ' · ' || dep.name, oc.completed_at, NULL::VARCHAR
                FROM online_consultations oc
                JOIN doctors d ON d.id = oc.doctor_id
                JOIN departments dep ON dep.id = d.department_id
                WHERE oc.patient_id = #{patientId} AND oc.health_profile_id = #{profileId}
                  AND oc.status = 'COMPLETED'
                UNION ALL
                SELECT 'REPORT_INTERPRETATION', r.id, '报告解读',
                       COALESCE((r.result_json::jsonb ->> 'summary'), '报告解读完成'), r.created_at, r.disclaimer
                FROM report_interpretations r
                WHERE r.patient_id = #{patientId} AND r.health_profile_id = #{profileId}
                  AND r.status = 'SUCCEEDED'
                UNION ALL
                SELECT 'MED_CHECKIN', mc.id, mc.medication_name || ' 服药打卡',
                       mc.dosage || ' · ' || mc.frequency, mc.checked_at, mc.disclaimer
                FROM med_checkin_records mc
                WHERE mc.patient_id = #{patientId} AND mc.health_profile_id = #{profileId}
                  AND mc.status = 'CHECKED'
            ) timeline
            ORDER BY occurred_at DESC, record_id DESC
            """)
    List<HealthTimelineEntry> selectTimeline(@Param("patientId") long patientId, @Param("profileId") long profileId);
}
