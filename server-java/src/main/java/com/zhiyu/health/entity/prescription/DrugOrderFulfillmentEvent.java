package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 履约事件（票 88，ADR-0035）：append-only，记录订单每次到达的状态、发生时间与操作 staff。
 * 只允许插入（B 端履约动作写入），不允许更新或删除历史事件，由 service 层保障。
 */
@Getter
@Setter
@TableName("drug_order_fulfillment_events")
public class DrugOrderFulfillmentEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long drugOrderId;
    private String status;
    private Long staffId;
    private OffsetDateTime createdAt;
}
