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
    String DETAIL_COLUMNS =
            """
            SELECT pr.*, d.name AS doctor_name, dep.name AS department_name,
                   pa.nickname AS patient_nickname, pa.id AS patient_id, s.schedule_date
            FROM prescriptions pr
            JOIN appointments a ON a.id = pr.appointment_id
            JOIN patients pa ON pa.id = a.patient_id
            JOIN doctors d ON d.id = pr.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            JOIN schedules s ON s.id = a.schedule_id
            """;

    @Select(DETAIL_COLUMNS + " WHERE pr.id = #{id}")
    Prescription selectDetailedById(@Param("id") long id);

    @Select("SELECT * FROM prescriptions WHERE appointment_id = #{appointmentId}")
    Prescription selectByAppointmentId(@Param("appointmentId") long appointmentId);

    @Select(DETAIL_COLUMNS + " WHERE pr.status = #{status} ORDER BY pr.created_at DESC")
    List<Prescription> selectForReview(@Param("status") String status);

    @Select(DETAIL_COLUMNS + " WHERE pa.id = #{patientId} AND pr.status = 'APPROVED' ORDER BY pr.reviewed_at DESC")
    List<Prescription> selectApprovedForPatient(@Param("patientId") long patientId);

    @Update(
            """
            UPDATE prescriptions SET status = #{status}, review_reason = #{reason},
              reviewed_by = #{reviewerId}, interpretation = #{interpretation},
              disclaimer = #{disclaimer}, reviewed_at = now()
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int review(
            @Param("id") long id,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("reviewerId") long reviewerId,
            @Param("interpretation") String interpretation,
            @Param("disclaimer") String disclaimer);
}
