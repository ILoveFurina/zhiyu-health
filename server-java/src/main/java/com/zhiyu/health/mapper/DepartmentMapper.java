package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Department;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {

    /** 院区实际科室列表：按医院分类 sort_order 再按科室 id 排序。 */
    @Select(
            """
            SELECT dep.id AS department_id, dep.name, dc.name AS category_name, dep.floor, dep.location
            FROM departments dep
            JOIN department_categories dc ON dc.id = dep.category_id
            WHERE dep.campus_id = #{campusId}
            ORDER BY dc.sort_order, dep.id
            """)
    List<CampusDepartmentRow> selectByCampusOrdered(@Param("campusId") long campusId);

    /**
     * 城市级「科类 → 标准科室」目录（票 49）：只保留在该城市有实际科室映射的标准科室。
     * 匹配只经 standard_department_id 外键，不做名称字符串相等（ADR-0027）。
     */
    @Select(
            """
            SELECT DISTINCT sd.category, sd.id, sd.name, sd.sort_order
            FROM standard_departments sd
            JOIN departments dep ON dep.standard_department_id = sd.id
            JOIN hospital_campuses c ON c.id = dep.campus_id
            WHERE c.city_code = #{cityCode}
            ORDER BY sd.category, sd.sort_order, sd.id
            """)
    List<StandardCatalogRow> selectStandardCatalogByCity(@Param("cityCode") String cityCode);

    /**
     * 标准科室跨医院号源（票 49）：city_code 硬筛选 + 今天起 14 天窗口 + is_active。
     * 以医生为主 LEFT JOIN 排班：窗口内无排班/全部约满的医生都保留（无排班时 s.* 为 NULL），
     * 由 service 计算 bookable 让端侧置灰，保证「无论是否有号都返回同一种结构」。
     * 距离为院区级 Haversine；PG 的 LEAST 忽略 NULL 参数会把无坐标场景错算成 0，
     * 故用 CASE WHEN 在无坐标参数时显式置 NULL。
     */
    @Select(
            """
            SELECT d.id AS doctor_id, d.name AS doctor_name, d.title, d.registration_fee,
                   h.id AS hospital_id, h.name AS hospital_name,
                   c.id AS campus_id, c.name AS campus_name,
                   CASE WHEN #{latitude,jdbcType=DOUBLE} IS NULL THEN NULL
                        ELSE 6371 * acos(LEAST(1.0,
                            sin(radians(c.latitude)) * sin(radians(#{latitude,jdbcType=DOUBLE}))
                          + cos(radians(c.latitude)) * cos(radians(#{latitude,jdbcType=DOUBLE}))
                          * cos(radians(#{longitude,jdbcType=DOUBLE} - c.longitude))
                   )) END AS distance_km,
                   s.id AS schedule_id, s.schedule_date, s.time_slot, s.remaining_slots
            FROM doctors d
            JOIN departments dep ON dep.id = d.department_id
            JOIN hospital_campuses c ON c.id = dep.campus_id
            JOIN hospitals h ON h.id = c.hospital_id
            LEFT JOIN schedules s ON s.doctor_id = d.id
                 AND s.is_active = TRUE
                 AND s.schedule_date >= #{fromDate}
                 AND s.schedule_date <= #{toDate}
            WHERE dep.standard_department_id = #{standardDepartmentId}
              AND c.city_code = #{cityCode}
            ORDER BY d.id, s.schedule_date,
                     CASE s.time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END, s.id
            """)
    List<DoctorSlotRow> selectDoctorSlotRows(
            @Param("standardDepartmentId") long standardDepartmentId,
            @Param("cityCode") String cityCode,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("longitude") Double longitude,
            @Param("latitude") Double latitude);

    /** 院区科室投影行：record 构造器顺序须与 SELECT 列顺序一致。 */
    record CampusDepartmentRow(long departmentId, String name, String categoryName, String floor, String location) {}

    /** 城市标准科室目录投影行。 */
    record StandardCatalogRow(String category, long id, String name, int sortOrder) {}

    /** 标准科室号源投影行（医生 × 排班；医生窗口内无排班时 schedule* 为 NULL）。 */
    record DoctorSlotRow(
            long doctorId,
            String doctorName,
            String title,
            BigDecimal registrationFee,
            long hospitalId,
            String hospitalName,
            long campusId,
            String campusName,
            Double distanceKm,
            Long scheduleId,
            LocalDate scheduleDate,
            String timeSlot,
            Integer remainingSlots) {}
}
