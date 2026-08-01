package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.ReportInterpretation;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReportInterpretationMapper extends BaseMapper<ReportInterpretation> {

    @Select(
            """
            SELECT *
            FROM report_interpretations
            WHERE patient_id = #{patientId}
            ORDER BY created_at DESC, id DESC
            """)
    List<ReportInterpretation> selectHistoryByPatient(@Param("patientId") long patientId);
}
