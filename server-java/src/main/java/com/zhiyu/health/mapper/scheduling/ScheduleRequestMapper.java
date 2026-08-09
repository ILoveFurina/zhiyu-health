package com.zhiyu.health.mapper.scheduling;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.scheduling.ScheduleRequest;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduleRequestMapper extends BaseMapper<ScheduleRequest> {

    // 审核列表联查：医生姓名/科室名/职称来自 doctors+departments，按申请时间倒序。
    String DETAIL_COLUMNS =
            """
            SELECT sr.*, d.name AS doctor_name, dep.name AS department_name, d.title AS title
            FROM schedule_requests sr
            JOIN doctors d ON d.id = sr.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            """;

    @Select(DETAIL_COLUMNS + " WHERE sr.status = #{status} ORDER BY sr.created_at DESC")
    List<ScheduleRequest> selectForReview(@Param("status") String status);

    @Select(DETAIL_COLUMNS + " WHERE sr.doctor_id = #{doctorId} ORDER BY sr.created_at DESC")
    List<ScheduleRequest> selectByDoctor(@Param("doctorId") long doctorId);

    @Select(DETAIL_COLUMNS + " WHERE sr.id = #{id}")
    ScheduleRequest selectDetailedById(@Param("id") long id);

    /** 挂号冻结校验：某排班是否存在待审核的停诊或调整号源申请（待审核期间不可挂号）。 */
    @Select(
            """
            SELECT COUNT(*) FROM schedule_requests
            WHERE target_schedule_id = #{scheduleId}
              AND action IN ('DISABLE','MODIFY')
              AND status = 'PENDING'
            """)
    int countPendingBlockingBySchedule(@Param("scheduleId") long scheduleId);

    /** 排班申请查重：同医生同日同时段是否已有待审核的新增排班申请。 */
    @Select(
            """
            SELECT COUNT(*) FROM schedule_requests
            WHERE doctor_id = #{doctorId}
              AND schedule_date = #{scheduleDate}
              AND time_slot = #{timeSlot}
              AND action = 'CREATE'
              AND status = 'PENDING'
            """)
    int countPendingCreateByDoctorDateSlot(
            @Param("doctorId") long doctorId,
            @Param("scheduleDate") java.time.LocalDate scheduleDate,
            @Param("timeSlot") String timeSlot);

    /**
     * 条件更新审核结果（并发安全）：WHERE status='PENDING' 保证并发审核只有一个决定生效，
     * 期望受影响 1 行，否则上层判 409。审核通过时 schedule_id 回填关联的排班行
     * （CREATE 新建行 / MODIFY-DISABLE 指向 target_schedule_id）。
     */
    @Update(
            """
            UPDATE schedule_requests SET status = #{status}, reviewed_by = #{reviewerId},
              review_reason = #{reason}, schedule_id = #{scheduleId}, reviewed_at = now()
            WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int review(
            @Param("id") long id,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("reviewerId") long reviewerId,
            @Param("scheduleId") Long scheduleId,
            @Param("expectedStatus") String expectedStatus);
}
