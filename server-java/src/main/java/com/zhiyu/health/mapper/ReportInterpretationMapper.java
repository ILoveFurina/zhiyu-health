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

    @Select("SELECT * FROM report_interpretations WHERE id = #{id} AND patient_id = #{patientId}")
    ReportInterpretation selectOwned(@Param("id") long id, @Param("patientId") long patientId);

    // 概要最近报告：按报告内检查/报告日期倒序（ISO 文本可字典序比较），缺失回退创建时间
    @Select(
            """
            SELECT * FROM report_interpretations
            WHERE patient_id = #{patientId} AND health_profile_id = #{profileId} AND status = 'SUCCEEDED'
            ORDER BY COALESCE(result_json::jsonb ->> 'sample_or_exam_date', result_json::jsonb ->> 'report_date')
                     DESC NULLS LAST,
                     created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ReportInterpretation> selectSucceededByProfile(
            @Param("patientId") long patientId, @Param("profileId") long profileId, @Param("limit") int limit);
}
