package com.zhiyu.health.rule;

import java.util.List;

/** 确定性规则结果；decision 与 messageType 取值来自 contracts/。 */
public record ContraindicationResult(
        String decision, String messageType, boolean blocked, List<String> reasons, String message, String advice) {
    public ContraindicationResult {
        reasons = List.copyOf(reasons);
    }
}
