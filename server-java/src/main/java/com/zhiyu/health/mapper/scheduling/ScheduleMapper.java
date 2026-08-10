package com.zhiyu.health.mapper.scheduling;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.scheduling.Schedule;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    // C 端按科室查可挂号源：NOT EXISTS 子查询排除存在待审核 DISABLE/MODIFY 申请的排班，
    // 口径与 selectBookableByDoctor 一致——这类排班在挂号入口会被 reserve 的冻结校验 409 拦截，
    // 若照样返回只会误导患者点击后失败；remaining_slots>0 只取未约满行。
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
              AND NOT EXISTS (
                  SELECT 1 FROM schedule_requests sr
                  WHERE sr.target_schedule_id = s.id
                    AND sr.action IN ('DISABLE','MODIFY')
                    AND sr.status = 'PENDING'
              )
            ORDER BY s.doctor_id, s.schedule_date,
                     CASE s.time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END
            """)
    List<Schedule> selectAvailableByDepartment(
            @Param("departmentName") String departmentName, @Param("fromDate") LocalDate fromDate);

    // C 端按医生查可挂号源：NOT EXISTS 子查询同上口径（排除待审核 DISABLE/MODIFY）。
    // 注意子查询 target_schedule_id = id 中的 id 解析为外层 schedules 表主键（单表查询无歧义），
    // 与 selectAvailableByDepartment 的 s.id 语义一致；保持无前缀仅为历史沿用，勿误读为列名缺失。
    @Select(
            """
            SELECT * FROM schedules
            WHERE doctor_id = #{doctorId}
              AND is_active = TRUE
              AND schedule_date >= #{fromDate}
              AND remaining_slots > 0
              AND NOT EXISTS (
                  SELECT 1 FROM schedule_requests sr
                  WHERE sr.target_schedule_id = id
                    AND sr.action IN ('DISABLE','MODIFY')
                    AND sr.status = 'PENDING'
              )
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

    // C 端找医生排班列表：只返回患者实际可挂号的排班。已停诊（is_active=FALSE）或存在待审核停诊申请的
    // 排班在挂号入口会被 404/409 确定性拦截（见 AppointmentService.reserve），若照样展示只会误导点击；
    // 过滤口径与挂号拦截条件保持一致。remaining_slots=0 的行保留，供端侧置灰展示"约满"。
    @Select(
            """
            SELECT s.*
            FROM schedules s
            WHERE s.doctor_id = #{doctorId}
              AND s.is_active = TRUE
              AND s.schedule_date >= #{fromDate}
              AND NOT EXISTS (
                  SELECT 1 FROM schedule_requests sr
                  WHERE sr.target_schedule_id = s.id
                    AND sr.action IN ('DISABLE','MODIFY')
                    AND sr.status = 'PENDING'
              )
            ORDER BY s.schedule_date,
                     CASE s.time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END,
                     s.id
            """)
    List<Schedule> selectBookableByDoctor(@Param("doctorId") long doctorId, @Param("fromDate") LocalDate fromDate);

    // 持锁取 registration_fee：与 AppointmentService.reserve 临界区共用同一把 schedule 行锁，
    // 保证扣号、序号分配、取费在同一事务内读到一致的排班快照，避免取费后行被并发停诊。
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

    // 停诊/复诊裸翻转无 CAS：排班不可硬删，停诊保留历史与号源对账；并发安全由审核流串行化保证
    // （disable/enable 只经 ScheduleRequestService 审核通过后调用，不会与患者挂号并发触达）。
    @Update("UPDATE schedules SET is_active = FALSE WHERE id = #{scheduleId}")
    int disable(@Param("scheduleId") long scheduleId);

    @Update("UPDATE schedules SET is_active = TRUE WHERE id = #{scheduleId}")
    int enable(@Param("scheduleId") long scheduleId);

    // 号源容量增量调整（排班号源修改审核通过后执行）：remaining 按 (newTotal-oldTotal) 增量平移，
    // 而非用新 total 覆盖，避免覆盖期间并发扣减丢失；WHERE remaining+delta>=0 防缩容把已售号源扣成负数。
    // 增量与 Redis 侧 SlotAccounting.withAdjustment 的 INCRBY 可交换，双写一致。
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

    // 挂号扣减号源 CAS：WHERE remaining_slots>0 乐观扣减，返回 0 即满号，由上层（SlotAccounting
    // 已 DECR Redis）回补后抛 409。与 Redis DECR 双写，PG 返回 0 时 Redis 预扣被反向补偿。
    @Update(
            """
            UPDATE schedules
            SET remaining_slots = remaining_slots - 1
            WHERE id = #{scheduleId} AND is_active = TRUE AND remaining_slots > 0
            """)
    int decrementRemainingSlots(@Param("scheduleId") long scheduleId);

    // 取消/超时回补号源 CAS：WHERE remaining_slots<total_slots 防回补超过总容量（停诊或审核调整
    // 期间 total 可能已变，上限守卫避免号源池溢出）。与 Redis INCR 双写，PG 返回 0 时 Redis 退还被撤销。
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
