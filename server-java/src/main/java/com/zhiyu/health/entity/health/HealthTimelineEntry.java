package com.zhiyu.health.entity.health;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 健康时间线的只读查询投影。 */
@Getter
@Setter
public class HealthTimelineEntry {
    private String type;
    private Long recordId;
    private String title;
    private String summary;
    private OffsetDateTime occurredAt;
    private String disclaimer;
}
