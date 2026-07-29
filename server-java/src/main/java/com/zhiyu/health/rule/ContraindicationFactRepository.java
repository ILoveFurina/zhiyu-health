package com.zhiyu.health.rule;

import java.util.List;

/** 禁忌事实只读 seam；实现只能读取 Neo4j，禁止落入业务 mapper。 */
public interface ContraindicationFactRepository {
    ContraindicationFacts load(List<Long> medicationIds);
}
