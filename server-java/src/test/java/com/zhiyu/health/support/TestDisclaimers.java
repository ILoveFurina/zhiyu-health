package com.zhiyu.health.support;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.DisclaimerService;

/** 测试用免责声明组件：加载仓库根真实契约，避免各测试类复制装配咒语或硬编码法定文案。 */
public final class TestDisclaimers {

    private TestDisclaimers() {}

    public static DisclaimerService instance() {
        return new DisclaimerService(Contracts.load(Contracts.resolveDir()));
    }
}
