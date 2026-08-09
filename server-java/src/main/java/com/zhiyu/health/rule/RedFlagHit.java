package com.zhiyu.health.rule;

/** 确定性红线规则的命中结果。 */
public record RedFlagHit(String ruleName, String advice) {}
