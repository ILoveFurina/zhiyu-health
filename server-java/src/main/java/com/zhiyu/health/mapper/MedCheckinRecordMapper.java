package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.MedCheckinRecord;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MedCheckinRecordMapper extends BaseMapper<MedCheckinRecord> {

    // 生成幂等：同一处方明细同一提醒日已存在则静默跳过，重复审核/重投不产生重复提醒（ADR-0017）。
    @Update(
            """
            INSERT INTO med_checkin_records
              (patient_id, health_profile_id, prescription_id, prescription_item_id,
               medication_name, dosage, frequency, due_date, status, disclaimer)
            VALUES
              (#{patientId}, #{healthProfileId}, #{prescriptionId}, #{prescriptionItemId},
               #{medicationName}, #{dosage}, #{frequency}, #{dueDate}, #{status}, #{disclaimer})
            ON CONFLICT (prescription_item_id, due_date) DO NOTHING
            """)
    int insertIgnore(MedCheckinRecord record);

    // 打卡幂等：只有 PENDING 行能被推进，affectedRows=1 首次、=0 重复或不存在，CHECKED 不可回退。
    @Update("UPDATE med_checkin_records SET status = #{checked}, checked_at = now() "
            + "WHERE id = #{id} AND status = #{pending}")
    int check(@Param("id") long id, @Param("checked") String checkedStatus, @Param("pending") String pendingStatus);

    // 消息页聚合：当前档案下到点未打卡的提醒，按提醒日升序（早的在前）。
    @Select(
            """
            SELECT * FROM med_checkin_records
            WHERE patient_id = #{patientId} AND health_profile_id = #{profileId}
              AND status = #{pending} AND due_date <= #{today}
            ORDER BY due_date ASC, id ASC
            """)
    List<MedCheckinRecord> selectPendingDue(
            @Param("patientId") long patientId,
            @Param("profileId") long profileId,
            @Param("today") LocalDate today,
            @Param("pending") String pendingStatus);

    // streak 现算：取该档案所有已打卡记录的 due_date 倒序，service 层从今天/昨天往前数连续。
    @Select(
            """
            SELECT due_date FROM med_checkin_records
            WHERE patient_id = #{patientId} AND health_profile_id = #{profileId}
              AND status = #{checked}
            ORDER BY due_date DESC
            """)
    List<LocalDate> selectCheckedDatesDescending(
            @Param("patientId") long patientId,
            @Param("profileId") long profileId,
            @Param("checked") String checkedStatus);

    @Select("SELECT * FROM med_checkin_records WHERE id = #{id} AND patient_id = #{patientId}")
    MedCheckinRecord selectOwned(@Param("id") long id, @Param("patientId") long patientId);
}
