package com.zhiyu.health.entity;

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
    private Integer totalSlots;
    private Integer remainingSlots;
    // 装箱 Boolean 时 Lombok 生成 getIsActive/setIsActive，与原手写签名一致
    private Boolean isActive;

    @TableField(exist = false)
    private BigDecimal registrationFee;
}
