package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.PreconsultationDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PreconsultationDraftMapper extends BaseMapper<PreconsultationDraft> {

    /** 同一患者同一档案的未提交草稿（活跃草稿由部分唯一索引保证最多一条）。 */
    @Select(
            """
            SELECT * FROM preconsultation_drafts
            WHERE patient_id = #{patientId} AND health_profile_id = #{healthProfileId}
              AND status IN (#{collecting}, #{pendingConfirm})
            ORDER BY id DESC LIMIT 1
            """)
    PreconsultationDraft selectActive(
            @Param("patientId") long patientId,
            @Param("healthProfileId") long healthProfileId,
            @Param("collecting") String collecting,
            @Param("pendingConfirm") String pendingConfirm);

    /**
     * 摘要快照落库：仅允许从未提交状态推进到 PENDING_CONFIRM。
     * 已提交草稿（SUBMITTED）快照不可再变，0 行属正常竞态，调用方静默保留上一版。
     */
    @Update(
            """
            UPDATE preconsultation_drafts
            SET chief_complaint = #{chiefComplaint}, present_illness = #{presentIllness},
                allergy_history = #{allergyHistory}, summary_disclaimer = #{summaryDisclaimer},
                suggested_standard_department_id = #{suggestedStandardDepartmentId,jdbcType=BIGINT},
                summary_updated_at = now(), status = #{pendingConfirm}, updated_at = now()
            WHERE id = #{id} AND status IN (#{collecting}, #{pendingConfirm})
            """)
    int applySummary(
            @Param("id") long id,
            @Param("chiefComplaint") String chiefComplaint,
            @Param("presentIllness") String presentIllness,
            @Param("allergyHistory") String allergyHistory,
            @Param("summaryDisclaimer") String summaryDisclaimer,
            @Param("suggestedStandardDepartmentId") Long suggestedStandardDepartmentId,
            @Param("collecting") String collecting,
            @Param("pendingConfirm") String pendingConfirm);

    /** 预问诊轮次惰性建会话后回填关联；重复回填同值幂等。 */
    @Update(
            """
            UPDATE preconsultation_drafts SET conversation_id = #{conversationId}, updated_at = now()
            WHERE id = #{id} AND conversation_id IS NULL
            """)
    int attachConversation(@Param("id") long id, @Param("conversationId") long conversationId);

    /** 确认摘要建单同事务的草稿提交：只从未提交状态推进，0 行即竞态失败需整体回滚。 */
    @Update(
            """
            UPDATE preconsultation_drafts SET status = #{submitted}, updated_at = now()
            WHERE id = #{id} AND status IN (#{collecting}, #{pendingConfirm})
            """)
    int markSubmitted(
            @Param("id") long id,
            @Param("submitted") String submitted,
            @Param("collecting") String collecting,
            @Param("pendingConfirm") String pendingConfirm);
}
