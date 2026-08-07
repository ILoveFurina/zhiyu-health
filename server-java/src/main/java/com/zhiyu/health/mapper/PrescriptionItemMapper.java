package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.PrescriptionItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PrescriptionItemMapper extends BaseMapper<PrescriptionItem> {
    @Select(
            """
            SELECT pi.*, m.name AS medication_name, m.generic_name, m.specification
            FROM prescription_items pi JOIN medications m ON m.id = pi.medication_id
            WHERE pi.prescription_id = #{prescriptionId} ORDER BY pi.id
            """)
    List<PrescriptionItem> selectDetailed(@Param("prescriptionId") long prescriptionId);

    // 票 56 双来源（fail-closed 安全回归重点）：禁忌"在用药"检查若 INNER JOIN appointments
    // 会漏掉在线问诊处方的在用药，方向是 fail-open，必须 LEFT JOIN 双来源 COALESCE 取档案。
    @Select(
            """
            SELECT DISTINCT pi.medication_id
            FROM prescription_items pi
            JOIN prescriptions p ON p.id = pi.prescription_id
            LEFT JOIN appointments a ON a.id = p.appointment_id
            LEFT JOIN online_consultations oc ON oc.id = p.online_consultation_id
            WHERE COALESCE(a.health_profile_id, oc.health_profile_id) = #{healthProfileId} AND p.status = #{status}
            ORDER BY pi.medication_id
            """)
    List<Long> selectMedicationIdsByHealthProfileAndStatus(
            @Param("healthProfileId") long healthProfileId, @Param("status") String status);
}
