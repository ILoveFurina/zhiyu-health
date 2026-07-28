package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    @Select("""
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
    List<Schedule> selectAvailableByDepartment(@Param("departmentName") String departmentName,
                                                @Param("fromDate") LocalDate fromDate);

    @Select("""
            SELECT * FROM schedules
            WHERE doctor_id = #{doctorId}
              AND is_active = TRUE
              AND schedule_date >= #{fromDate}
              AND remaining_slots > 0
            ORDER BY schedule_date,
                     CASE time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END
            """)
    List<Schedule> selectAvailableByDoctor(@Param("doctorId") long doctorId,
                                           @Param("fromDate") LocalDate fromDate);

    @Select("SELECT * FROM schedules WHERE id = #{scheduleId} FOR UPDATE")
    Schedule selectByIdForUpdate(@Param("scheduleId") long scheduleId);

    @Update("UPDATE schedules SET is_active = FALSE WHERE id = #{scheduleId}")
    int disable(@Param("scheduleId") long scheduleId);

    @Update("""
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

    @Update("""
            UPDATE schedules
            SET remaining_slots = remaining_slots - 1
            WHERE id = #{scheduleId} AND is_active = TRUE AND remaining_slots > 0
            """)
    int decrementRemainingSlots(@Param("scheduleId") long scheduleId);
}
