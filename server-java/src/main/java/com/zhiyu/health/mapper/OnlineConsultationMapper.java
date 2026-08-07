package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.OnlineConsultation;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OnlineConsultationMapper extends BaseMapper<OnlineConsultation> {

    /** 详情联查列：标准科室名恒带（C/B 端展示），患者与档案信息仅科室池/医生视图使用；
     * 诊断/医嘱自票 55 起 LEFT JOIN consultation_records 投影（问诊单不再持有该两列）。 */
    String DETAIL_COLUMNS =
            """
            SELECT oc.*, sd.name AS standard_department_name,
                   p.nickname AS patient_nickname,
                   hp.display_name AS profile_display_name, hp.gender AS profile_gender,
                   hp.birth_date AS profile_birth_date, hp.relationship AS profile_relationship,
                   cr.diagnosis, cr.advice
            FROM online_consultations oc
            JOIN standard_departments sd ON sd.id = oc.standard_department_id
            JOIN patients p ON p.id = oc.patient_id
            JOIN health_profiles hp ON hp.id = oc.health_profile_id
            LEFT JOIN consultation_records cr ON cr.online_consultation_id = oc.id
            """;

    @Select(DETAIL_COLUMNS + " WHERE oc.id = #{id}")
    OnlineConsultation selectDetailedById(@Param("id") long id);

    @Select(DETAIL_COLUMNS + " WHERE oc.id = #{id} AND oc.patient_id = #{patientId}")
    OnlineConsultation selectDetailedByIdAndPatient(@Param("id") long id, @Param("patientId") long patientId);

    /** 草稿的最近一条问诊单：已提交草稿重复确认只返回该单（幂等回放）。 */
    @Select(DETAIL_COLUMNS + " WHERE oc.draft_id = #{draftId} ORDER BY oc.id DESC LIMIT 1")
    OnlineConsultation selectLatestByDraftId(@Param("draftId") long draftId);

    /** 档案的活跃（待接诊/进行中）问诊单：并发确认撞部分唯一索引后改查本行返回。 */
    @Select(DETAIL_COLUMNS
            + " WHERE oc.health_profile_id = #{healthProfileId}"
            + " AND oc.status IN (#{waiting}, #{inProgress}) ORDER BY oc.id DESC LIMIT 1")
    OnlineConsultation selectActiveByProfile(
            @Param("healthProfileId") long healthProfileId,
            @Param("waiting") String waiting,
            @Param("inProgress") String inProgress);

    @Select(DETAIL_COLUMNS + " WHERE oc.patient_id = #{patientId} ORDER BY oc.id DESC")
    List<OnlineConsultation> selectByPatient(@Param("patientId") long patientId);

    /**
     * 科室待接诊池（Spec 0003）：平台范围按标准科室路由，不含城市/医院/院区/排班/号源条件；
     * 已过期的 WAITING_DOCTOR 由调用方先惰性收敛，此处只查未过期单。
     */
    @Select(DETAIL_COLUMNS
            + " WHERE oc.status = #{waiting} AND oc.standard_department_id = #{standardDepartmentId}"
            + " AND oc.expires_at > now() ORDER BY oc.id ASC")
    List<OnlineConsultation> selectPool(
            @Param("standardDepartmentId") long standardDepartmentId, @Param("waiting") String waiting);

    /** 医生本人接诊记录：可选状态过滤（IN_PROGRESS/COMPLETED），#{status} 为空查全部。 */
    @Select(DETAIL_COLUMNS
            + " WHERE oc.doctor_id = #{doctorId}"
            + " AND (#{status,jdbcType=VARCHAR} IS NULL OR oc.status = #{status,jdbcType=VARCHAR})"
            + " ORDER BY oc.id DESC")
    List<OnlineConsultation> selectMine(@Param("doctorId") long doctorId, @Param("status") String status);

    /**
     * 原子接受（Spec 0003）：单条条件更新同时校验预期状态、未绑定医生与未过期，
     * 并发接受只有 affected rows = 1 的医生成功，其余由调用方转明确冲突。
     */
    @Update(
            """
            UPDATE online_consultations
            SET status = #{inProgress}, doctor_id = #{doctorId}, accepted_at = now(), updated_at = now()
            WHERE id = #{id} AND status = #{waiting} AND doctor_id IS NULL AND expires_at > now()
            """)
    int accept(
            @Param("id") long id,
            @Param("doctorId") long doctorId,
            @Param("waiting") String waiting,
            @Param("inProgress") String inProgress);

    /** 惰性失效收敛：列表/详情/池/接受入口先执行，把过期待接诊单收敛为 EXPIRED（无调度中间件）。 */
    @Update(
            """
            UPDATE online_consultations SET status = #{expired}, updated_at = now()
            WHERE status = #{waiting} AND expires_at <= now()
            """)
    int expireOverdue(@Param("waiting") String waiting, @Param("expired") String expired);

    /** 患者取消：条件更新限定本人与待接诊状态，重复取消由调用方先短路（幂等）。 */
    @Update(
            """
            UPDATE online_consultations SET status = #{cancelled}, cancelled_at = now(), updated_at = now()
            WHERE id = #{id} AND patient_id = #{patientId} AND status = #{waiting}
            """)
    int cancel(
            @Param("id") long id,
            @Param("patientId") long patientId,
            @Param("waiting") String waiting,
            @Param("cancelled") String cancelled);

    /** 首次发起接诊方式：方式一旦设定不可更换（条件 consult_method IS NULL 兜底竞态）。 */
    @Update(
            """
            UPDATE online_consultations
            SET consult_method = #{method}, method_started_at = now(), updated_at = now()
            WHERE id = #{id} AND doctor_id = #{doctorId} AND status = #{inProgress} AND consult_method IS NULL
            """)
    int startMethod(
            @Param("id") long id,
            @Param("doctorId") long doctorId,
            @Param("inProgress") String inProgress,
            @Param("method") String method);

    /** 完成问诊：只推进状态机；诊断/医嘱由调用方同事务写 consultation_records（票 55）。 */
    @Update(
            """
            UPDATE online_consultations
            SET status = #{completed}, completed_at = now(), updated_at = now()
            WHERE id = #{id} AND doctor_id = #{doctorId} AND status = #{inProgress}
            """)
    int complete(
            @Param("id") long id,
            @Param("doctorId") long doctorId,
            @Param("inProgress") String inProgress,
            @Param("completed") String completed);

    /** 医生实际科室映射的标准科室：科室池可见性与接诊资格的唯一判据，未映射返回 NULL。 */
    @Select(
            """
            SELECT d.standard_department_id FROM doctors doc
            JOIN departments d ON doc.department_id = d.id
            WHERE doc.id = #{doctorId}
            """)
    Long selectStandardDepartmentIdByDoctor(@Param("doctorId") long doctorId);

    /** C 端医生身份视图：接受后患者轮询获得可信医生姓名/职称/医院/科室。 */
    @Select(
            """
            SELECT doc.name, doc.title, h.name AS hospital_name, dep.name AS department_name
            FROM doctors doc
            JOIN departments dep ON doc.department_id = dep.id
            JOIN hospital_campuses c ON dep.campus_id = c.id
            JOIN hospitals h ON c.hospital_id = h.id
            WHERE doc.id = #{doctorId}
            """)
    DoctorIdentityRow selectDoctorIdentity(@Param("doctorId") long doctorId);

    /** 医生身份投影行：record 构造器顺序须与 SELECT 列顺序一致。 */
    record DoctorIdentityRow(String name, String title, String hospitalName, String departmentName) {}
}
