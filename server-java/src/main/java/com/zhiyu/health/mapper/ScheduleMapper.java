package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

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
