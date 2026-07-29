package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.InAppMessage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InAppMessageMapper extends BaseMapper<InAppMessage> {
    @Select("SELECT * FROM in_app_messages WHERE patient_id = #{patientId} ORDER BY created_at DESC")
    List<InAppMessage> selectForPatient(@Param("patientId") long patientId);

    @Select("SELECT * FROM in_app_messages WHERE related_appointment_id = #{appointmentId} AND type = #{type}")
    InAppMessage selectByAppointmentAndType(@Param("appointmentId") long appointmentId, @Param("type") String type);
}
