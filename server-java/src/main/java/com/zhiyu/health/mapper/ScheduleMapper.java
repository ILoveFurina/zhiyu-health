package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    @Update("""
            UPDATE schedules
            SET remaining_slots = remaining_slots - 1
            WHERE id = #{scheduleId} AND is_active = TRUE AND remaining_slots > 0
            """)
    int decrementRemainingSlots(@Param("scheduleId") long scheduleId);
}
