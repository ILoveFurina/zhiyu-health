package com.zhiyu.health.entity.common;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("in_app_messages")
public class InAppMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private String type;
    private String title;
    private String content;
    private String disclaimer;
    private Long relatedAppointmentId;
    // 票 60：审核结果消息挂处方、随访消息挂在线问诊，各配 (外键, type) UNIQUE 兜底幂等
    private Long relatedPrescriptionId;
    private Long relatedOnlineConsultationId;
    // 延迟可见：随访消息为未来时间；null 时走 DB 默认 now()（即时消息）
    private OffsetDateTime visibleAt;
    private OffsetDateTime createdAt;
}
