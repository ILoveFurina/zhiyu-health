package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.HealthObservation;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HealthObservationMapper extends BaseMapper<HealthObservation> {

    // 跨报告同日槽位冲突交给 ON CONFLICT（部分唯一索引 uq_health_observations_current_slot），
    // 禁止先查后改；影响行数 0 即 DUPLICATE_SLOT（详情读取时推导，不落库）。
    @Update(
            """
            INSERT INTO health_observations
              (health_profile_id, report_interpretation_id, metric_code, value_numeric, value_category,
               unit, reference_range, observed_on, source_type, verification_status, current)
            VALUES
              (#{healthProfileId}, #{reportInterpretationId}, #{metricCode}, #{valueNumeric}, #{valueCategory},
               #{unit}, #{referenceRange}, #{observedOn}, #{sourceType}, #{verificationStatus}, #{current})
            ON CONFLICT (health_profile_id, metric_code, observed_on) WHERE current = TRUE DO NOTHING
            """)
    int insertIgnoreSlot(HealthObservation record);

    // 归属校验：观测经 health_profiles 归属到 patient_id，越权返回 null（404，不泄露存在性）。
    @Select(
            """
            SELECT o.* FROM health_observations o
            JOIN health_profiles p ON p.id = o.health_profile_id
            WHERE o.id = #{id} AND p.patient_id = #{patientId}
            """)
    HealthObservation selectOwned(@Param("id") long id, @Param("patientId") long patientId);

    // 确认幂等：只有 current 的 UNVERIFIED 行能被推进，affectedRows=1 首次、=0 重复或状态已变迁。
    @Update("UPDATE health_observations SET verification_status = #{confirmed}, updated_at = now() "
            + "WHERE id = #{id} AND current = TRUE AND verification_status = #{unverified}")
    int confirm(@Param("id") long id, @Param("confirmed") String confirmed, @Param("unverified") String unverified);

    // 纠错抢占：条件 UPDATE 把旧记录转为 SUPERSEDED/current=FALSE，0 行说明并发已改状态（409）。
    @Update("UPDATE health_observations SET verification_status = #{superseded}, current = FALSE, updated_at = now() "
            + "WHERE id = #{id} AND current = TRUE AND verification_status IN (#{unverified}, #{confirmed})")
    int supersede(
            @Param("id") long id,
            @Param("superseded") String superseded,
            @Param("unverified") String unverified,
            @Param("confirmed") String confirmed);

    // 排除终态：保持 current=TRUE 占用每日槽位，阻止同槽位重复上传复活；无恢复端点。
    @Update("UPDATE health_observations SET verification_status = #{rejected}, updated_at = now() "
            + "WHERE id = #{id} AND current = TRUE AND verification_status IN (#{unverified}, #{confirmed})")
    int reject(
            @Param("id") long id,
            @Param("rejected") String rejected,
            @Param("unverified") String unverified,
            @Param("confirmed") String confirmed);

    // 有效投影：概要/趋势/指标列表只读 current 且未被排除的观测，按检查日升序。
    @Select(
            """
            SELECT o.* FROM health_observations o
            JOIN health_profiles p ON p.id = o.health_profile_id
            WHERE p.patient_id = #{patientId} AND o.health_profile_id = #{profileId}
              AND o.current = TRUE AND o.verification_status IN (#{unverified}, #{confirmed})
            ORDER BY o.observed_on ASC, o.id ASC
            """)
    List<HealthObservation> selectEffective(
            @Param("patientId") long patientId,
            @Param("profileId") long profileId,
            @Param("unverified") String unverified,
            @Param("confirmed") String confirmed);

    // 报告详情沉淀状态推导：取该报告全部观测（含历史版本），由 service 按 current/状态推导。
    @Select("SELECT * FROM health_observations WHERE report_interpretation_id = #{reportId} ORDER BY id ASC")
    List<HealthObservation> selectByReport(@Param("reportId") long reportId);
}
