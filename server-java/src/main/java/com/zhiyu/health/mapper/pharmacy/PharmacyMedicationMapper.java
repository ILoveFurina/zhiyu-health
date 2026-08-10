package com.zhiyu.health.mapper.pharmacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.pharmacy.PharmacyAvailability;
import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.entity.prescription.Medication;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PharmacyMedicationMapper extends BaseMapper<PharmacyMedication> {

    String DETAIL_COLUMNS =
            """
            SELECT pm.*, m.name AS medication_name, m.generic_name, m.specification, m.is_prescription
            FROM pharmacy_medications pm
            JOIN medications m ON m.id = pm.medication_id
            """;

    @Select(
            """
            <script>
            """ + DETAIL_COLUMNS
                    + """
            WHERE pm.pharmacy_id = #{pharmacyId}
            <if test='keyword != null and keyword != ""'>
                AND m.name ILIKE CONCAT('%', #{keyword}, '%')
            </if>
            ORDER BY m.name, pm.id
            </script>
            """)
    List<PharmacyMedication> selectDetailedByPharmacy(
            @Param("pharmacyId") long pharmacyId, @Param("keyword") String keyword);

    @Select(DETAIL_COLUMNS + " WHERE pm.id = #{id}")
    PharmacyMedication selectDetailedById(@Param("id") long id);

    // 医生开方目录（票 88）：只能选自己当前院区药房已配置且在售的标准药品。
    @Select(
            """
            <script>
            SELECT m.* FROM medications m
            JOIN pharmacy_medications pm ON pm.medication_id = m.id
            JOIN campus_pharmacies cp ON cp.id = pm.pharmacy_id
            WHERE cp.campus_id = #{campusId} AND pm.is_on_sale = TRUE
            <if test='keyword != null and keyword != ""'>
                AND m.name ILIKE CONCAT('%', #{keyword}, '%')
            </if>
            ORDER BY m.name
            </script>
            """)
    List<Medication> selectOnSaleCatalogByCampus(@Param("campusId") long campusId, @Param("keyword") String keyword);

    // 开方提交侧复验：按 id 集合取本院区药房在售的药品行（同时用于目录校验与名称/规格回填）。
    @Select(
            """
            <script>
            SELECT m.* FROM medications m
            JOIN pharmacy_medications pm ON pm.medication_id = m.id
            JOIN campus_pharmacies cp ON cp.id = pm.pharmacy_id
            WHERE cp.campus_id = #{campusId} AND pm.is_on_sale = TRUE
            AND m.id IN
            <foreach collection="medicationIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY m.id
            </script>
            """)
    List<Medication> selectOnSaleByCampusAndIds(
            @Param("campusId") long campusId, @Param("medicationIds") List<Long> medicationIds);

    // 下单事务行锁（票 88）：按 medication_id 固定序加锁（防死锁），锁定本药房的在售关系行，
    // 成交价与库存以锁内读取为准（禁止先查后改，扣减仍走带 stock >= n 条件的 UPDATE）。
    @Select(
            """
            <script>
            SELECT pm.*, m.name AS medication_name, m.generic_name, m.specification, m.is_prescription
            FROM pharmacy_medications pm
            JOIN medications m ON m.id = pm.medication_id
            WHERE pm.pharmacy_id = #{pharmacyId}
            AND pm.medication_id IN
            <foreach collection="medicationIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY pm.medication_id
            FOR UPDATE OF pm
            </script>
            """)
    List<PharmacyMedication> selectForUpdateByPharmacyAndMedicationIds(
            @Param("pharmacyId") long pharmacyId, @Param("medicationIds") List<Long> medicationIds);

    // 库存只能由带 stock >= n 条件的 UPDATE 预扣；0 行即库存不足，事务回滚此前扣减。
    @Update(
            """
            UPDATE pharmacy_medications SET stock = stock - #{quantity}
            WHERE id = #{id} AND stock >= #{quantity}
            """)
    int deductStock(@Param("id") long id, @Param("quantity") int quantity);

    // 取消/过期回补：与扣减同事务提交，保证跨入口不重复回补（订单行锁 + 条件状态更新裁决）。
    @Update("UPDATE pharmacy_medications SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") long id, @Param("quantity") int quantity);

    // OTC 候选（票 88）：当前服务城市由院区动态聚合（请求无 city 入参，demo 单服务城市口径即
    // 全部院区）；只读测算不加锁，返回全部院区药房在售 OTC 行，由 service 按药房分组过滤整单满足。
    @Select(
            """
            <script>
            SELECT pm.id AS pharmacy_medication_id, pm.medication_id, pm.price, pm.stock,
                   cp.id AS pharmacy_id, cp.display_name AS pharmacy_display_name,
                   cp.delivery_fee, cp.estimated_delivery_minutes,
                   h.name AS hospital_name, hc.name AS campus_name, hc.address AS campus_address,
                   hc.city_name, hc.longitude AS campus_longitude, hc.latitude AS campus_latitude
            FROM pharmacy_medications pm
            JOIN medications m ON m.id = pm.medication_id
            JOIN campus_pharmacies cp ON cp.id = pm.pharmacy_id
            JOIN hospital_campuses hc ON hc.id = cp.campus_id
            JOIN hospitals h ON h.id = hc.hospital_id
            WHERE pm.is_on_sale = TRUE AND m.is_prescription = FALSE
            AND pm.medication_id IN
            <foreach collection="medicationIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY h.name, hc.name, pm.medication_id
            </script>
            """)
    List<PharmacyAvailability> selectOtcAvailability(@Param("medicationIds") List<Long> medicationIds);

    // 删除规则（票 88）：处方明细只存标准药品外键，药房药品的「处方引用」按
    // 「同药品 + 处方来源院区 = 本药房院区」判定；有引用即只允许下架，不可物理删除。
    @Select(
            """
            SELECT COUNT(*) FROM prescription_items pi
            JOIN prescriptions pr ON pr.id = pi.prescription_id
            WHERE pi.medication_id = #{medicationId} AND pr.source_campus_id = #{campusId}
            """)
    long countPrescriptionReferences(@Param("medicationId") long medicationId, @Param("campusId") long campusId);
}
