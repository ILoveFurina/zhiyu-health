package com.zhiyu.health.entity.scheduling;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("schedules")
public class Schedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long doctorId;
    private LocalDate scheduleDate;
    private TimeSlot timeSlot;
    // 号源容量计数器，不变式 0<=remaining<=total；并发扣减经 decrement/incrementRemainingSlots 的 CAS 守卫，
    // 与 Redis 计数双写对账（SlotAccounting），禁止先查后改。
    private Integer totalSlots;
    private Integer remainingSlots;
    // 停诊标志：由 disable/enable 翻转，受 schedule_request 审核流约束（待审核 DISABLE/MODIFY 期间冻结挂号）。
    // 装箱 Boolean 时 Lombok 生成 getIsActive/setIsActive，与原手写签名一致
    private Boolean isActive;

    @TableField(exist = false)
    private BigDecimal registrationFee;

    /** 联查投影：该排班是否存在待审核的 MODIFY/DISABLE/ENABLE 申请（排班表页面展示"待审核"状态用）。 */
    @TableField(exist = false)
    private String pendingAction;
}
