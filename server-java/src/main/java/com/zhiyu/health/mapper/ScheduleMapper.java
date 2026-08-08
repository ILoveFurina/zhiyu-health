package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Schedule;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    @Select(
            """
            SELECT s.*
            FROM schedules s
            JOIN doctors d ON d.id = s.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            WHERE dep.name = #{departmentName}
              AND s.is_active = TRUE
              AND s.schedule_date >= #{fromDate}
              AND s.remaining_slots > 0
            ORDER BY s.doctor_id, s.schedule_date,
                     CASE s.time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END
            """)
    List<Schedule> selectAvailableByDepartment(
            @Param("departmentName") String departmentName, @Param("fromDate") LocalDate fromDate);

    @Select(
            """
            SELECT * FROM schedules
            WHERE doctor_id = #{doctorId}
              AND is_active = TRUE
              AND schedule_date >= #{fromDate}
              AND remaining_slots > 0
            ORDER BY schedule_date,
                     CASE time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END
            """)
    List<Schedule> selectAvailableByDoctor(@Param("doctorId") long doctorId, @Param("fromDate") LocalDate fromDate);

    // 医生排班表：返回未来全部排班（含已停诊，供医生对已停诊排班发起恢复出诊申请），
    // 子查询带出该排班是否存在待审核的 DISABLE/ENABLE 申请（排班表页面展示"待审核"状态用）。
    @Select(
            """
            SELECT s.*, (
                SELECT sr.action FROM schedule_requests sr
                WHERE sr.target_schedule_id = s.id AND sr.status = 'PENDING'
                ORDER BY sr.created_at DESC LIMIT 1
            ) AS pending_action
            FROM schedules s
            WHERE s.doctor_id = #{doctorId}
              AND s.schedule_date >= #{fromDate}
            ORDER BY s.schedule_date,
                     CASE s.time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END,
                     s.id
            """)
    List<Schedule> selectFutureByDoctor(@Param("doctorId") long doctorId, @Param("fromDate") LocalDate fromDate);

    @Select(
            """
            SELECT s.*, d.registration_fee
            FROM schedules s
            JOIN doctors d ON d.id = s.doctor_id
            WHERE s.id = #{scheduleId}
            FOR UPDATE OF s
            """)
    Schedule selectByIdForUpdate(@Param("scheduleId") long scheduleId);

    /** 排班申请查重：同医生同日同时段是否已有活跃排班（CREATE 申请提交前校验）。 */
    @Select(
            """
            SELECT COUNT(*) FROM schedules
            WHERE doctor_id = #{doctorId}
              AND schedule_date = #{scheduleDate}
              AND time_slot = #{timeSlot}
              AND is_active = TRUE
            """)
    int countActiveByDoctorDateSlot(
            @Param("doctorId") long doctorId,
            @Param("scheduleDate") LocalDate scheduleDate,
            @Param("timeSlot") String timeSlot);

    @Update("UPDATE schedules SET is_active = FALSE WHERE id = #{scheduleId}")
    int disable(@Param("scheduleId") long scheduleId);

    @Update("UPDATE schedules SET is_active = TRUE WHERE id = #{scheduleId}")
    int enable(@Param("scheduleId") long scheduleId);

    @Update(
            """
            UPDATE schedules
            SET doctor_id = #{schedule.doctorId},
                schedule_date = #{schedule.scheduleDate},
                time_slot = #{schedule.timeSlot},
                remaining_slots = remaining_slots + (#{schedule.totalSlots} - total_slots),
                total_slots = #{schedule.totalSlots}
            WHERE id = #{schedule.id}
              AND remaining_slots + (#{schedule.totalSlots} - total_slots) >= 0
            """)
    int adjustCapacity(@Param("schedule") Schedule schedule);

    @Update(
            """
            UPDATE schedules
            SET remaining_slots = remaining_slots - 1
            WHERE id = #{scheduleId} AND is_active = TRUE AND remaining_slots > 0
            """)
    int decrementRemainingSlots(@Param("scheduleId") long scheduleId);

    @Update(
            """
            UPDATE schedules
            SET remaining_slots = remaining_slots + 1
            WHERE id = #{scheduleId} AND remaining_slots < total_slots
            """)
    int incrementRemainingSlots(@Param("scheduleId") long scheduleId);

    /**
     * 挂号后就诊指引卡数据来源（票 43/49）：联查排班->医生->科室->院区->医院，取关怀消息 content 所需静态字段。
     * 地址/floor/materials/precautions 一律取院区静态值（票 49 从医院下沉），历史挂号据此追溯到正确院区，
     * 不得从医院旧字段兜底。hospital_campuses 的这些列是演示用虚构静态 seed 值，非 LLM 生成。
     */
    @org.apache.ibatis.annotations.Select(
            """
            SELECT s.schedule_date AS scheduleDate, s.time_slot AS timeSlotValue,
                   d.name AS doctorName, dep.name AS departmentName,
                   h.name AS hospitalName, c.address AS address,
                   c.floor AS floor, c.materials AS materials, c.precautions AS precautions
            FROM schedules s
            JOIN doctors d ON d.id = s.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            JOIN hospital_campuses c ON c.id = dep.campus_id
            JOIN hospitals h ON h.id = c.hospital_id
            WHERE s.id = #{scheduleId}
            """)
    CareContext selectCareContextBySchedule(@Param("scheduleId") long scheduleId);

    /** 就诊指引卡拼装上下文：record 字段名与 SELECT 别名一致。 */
    record CareContext(
            java.time.LocalDate scheduleDate,
            String timeSlotValue,
            String doctorName,
            String departmentName,
            String hospitalName,
            String address,
            String floor,
            String materials,
            String precautions) {}
}
