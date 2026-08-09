package com.zhiyu.health.mapper.consultation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.consultation.ConsultationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConsultationRecordMapper extends BaseMapper<ConsultationRecord> {

    @Select("SELECT * FROM consultation_records WHERE appointment_id = #{appointmentId}")
    ConsultationRecord selectByAppointmentId(@Param("appointmentId") long appointmentId);
}
