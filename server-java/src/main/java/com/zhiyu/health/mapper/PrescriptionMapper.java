package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Prescription;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PrescriptionMapper extends BaseMapper<Prescription> {
    // 票 56 双来源：处方经 appointment_id 或 online_consultation_id 二选一关联（schema XOR 保证），
    // 患者/档案/发生时间一律 COALESCE 两来源取值，绝不能 INNER JOIN appointments 漏掉在线处方；
    // 在线问诊无排班，schedule_date 投影取问诊发生日期而非伪造排班日期。
    String DETAIL_COLUMNS =
            """
            SELECT pr.*, d.name AS doctor_name, dep.name AS department_name,
                   hp.display_name AS patient_nickname,
                   COALESCE(a.patient_id, oc.patient_id) AS patient_id,
                   COALESCE(a.health_profile_id, oc.health_profile_id) AS health_profile_id,
                   COALESCE(a.created_at, oc.created_at) AS occurred_at,
                   COALESCE(s.schedule_date, oc.created_at::date) AS schedule_date,
                   cr.diagnosis, cr.advice
            FROM prescriptions pr
            LEFT JOIN appointments a ON a.id = pr.appointment_id
            LEFT JOIN online_consultations oc ON oc.id = pr.online_consultation_id
            JOIN health_profiles hp ON hp.id = COALESCE(a.health_profile_id, oc.health_profile_id)
            JOIN doctors d ON d.id = pr.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            LEFT JOIN schedules s ON s.id = a.schedule_id
            LEFT JOIN consultation_records cr
                ON cr.appointment_id = a.id OR cr.online_consultation_id = oc.id
            """;

    @Select(DETAIL_COLUMNS + " WHERE pr.id = #{id}")
    Prescription selectDetailedById(@Param("id") long id);

    @Select("SELECT * FROM prescriptions WHERE appointment_id = #{appointmentId}")
    Prescription selectByAppointmentId(@Param("appointmentId") long appointmentId);

    @Select("SELECT * FROM prescriptions WHERE online_consultation_id = #{onlineConsultationId}")
    Prescription selectByOnlineConsultationId(@Param("onlineConsultationId") long onlineConsultationId);

    @Select(
            """
            SELECT pr.* FROM prescriptions pr
            LEFT JOIN appointments a ON a.id = pr.appointment_id
            LEFT JOIN online_consultations oc ON oc.id = pr.online_consultation_id
            WHERE pr.id = #{id} AND COALESCE(a.patient_id, oc.patient_id) = #{patientId}
            """)
    Prescription selectForPatient(@Param("id") long id, @Param("patientId") long patientId);

    @Select(DETAIL_COLUMNS + " WHERE pr.status = #{status} ORDER BY pr.created_at DESC")
    List<Prescription> selectForReview(@Param("status") String status);

    @Select(DETAIL_COLUMNS
            + " WHERE COALESCE(a.patient_id, oc.patient_id) = #{patientId}"
            + " AND COALESCE(a.health_profile_id, oc.health_profile_id) = #{profileId}"
            + " AND pr.status = #{status} ORDER BY pr.reviewed_at DESC")
    List<Prescription> selectApprovedForProfile(
            @Param("patientId") long patientId, @Param("profileId") long profileId, @Param("status") String status);

    @Update(
            """
            UPDATE prescriptions SET status = #{status}, review_reason = #{reason},
              reviewed_by = #{reviewerId}, interpretation = #{interpretation},
              disclaimer = #{disclaimer}, reviewed_at = now()
            WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int review(
            @Param("id") long id,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("reviewerId") long reviewerId,
            @Param("interpretation") String interpretation,
            @Param("disclaimer") String disclaimer,
            @Param("expectedStatus") String expectedStatus);
}
