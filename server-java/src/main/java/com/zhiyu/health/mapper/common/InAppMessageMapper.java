package com.zhiyu.health.mapper.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.common.InAppMessage;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InAppMessageMapper extends BaseMapper<InAppMessage> {
    // 随访等延迟消息 visible_at 在未来，到点才对患者可见（票 60）；即时消息默认 now() 不受影响
    @Select(
            "SELECT * FROM in_app_messages WHERE patient_id = #{patientId} AND visible_at <= now() ORDER BY created_at DESC")
    List<InAppMessage> selectForPatient(@Param("patientId") long patientId);

    @Select("SELECT * FROM in_app_messages WHERE related_appointment_id = #{appointmentId} AND type = #{type}")
    InAppMessage selectByAppointmentAndType(@Param("appointmentId") long appointmentId, @Param("type") String type);

    /**
     * 事件类消息幂等写入（票 60）：ON CONFLICT DO NOTHING 让 UNIQUE(来源外键, type) 在数据库层兜底——
     * 并发/重试撞约束时返回 0 且事务不受损（PG 约束违例会 abort 整个事务，Java 侧 catch 无法挽救，
     * 故不能用 try/catch DuplicateKeyException 实现幂等）。visible_at 传 null 走 COALESCE 取 now()，
     * 与即时消息默认语义一致；返回受影响行数（0=已存在，幂等吞掉）。
     */
    @Insert(
            """
            INSERT INTO in_app_messages
                (patient_id, type, title, content, disclaimer,
                 related_appointment_id, related_prescription_id, related_online_consultation_id, visible_at)
            VALUES (#{patientId}, #{type}, #{title}, #{content}, #{disclaimer},
                    #{relatedAppointmentId}, #{relatedPrescriptionId}, #{relatedOnlineConsultationId},
                    COALESCE(#{visibleAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}, now()))
            ON CONFLICT DO NOTHING
            """)
    int insertIgnoreConflict(InAppMessage message);
}
