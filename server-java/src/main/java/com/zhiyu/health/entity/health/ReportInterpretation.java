package com.zhiyu.health.entity.health;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 报告解读业务记录；原始报告文件不持久化。 */
@Getter
@Setter
@TableName("report_interpretations")
public class ReportInterpretation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long healthProfileId;
    private Long conversationId;
    private String requestId;
    private String fileType;
    private String fileName;
    private Integer pageCount;
    private String status;
    private String resultJson;
    private String contextSummary;
    private String errorCode;
    private String disclaimer;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
