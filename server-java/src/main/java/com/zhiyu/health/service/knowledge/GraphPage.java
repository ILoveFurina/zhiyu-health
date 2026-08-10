package com.zhiyu.health.service.knowledge;

import java.util.List;

/** 图谱管理列表页通用分页信封。 */
public record GraphPage<T>(long total, List<T> items) {}
