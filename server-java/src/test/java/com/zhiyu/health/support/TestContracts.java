package com.zhiyu.health.support;

import com.zhiyu.health.config.Contracts;

/** 测试用契约组件：加载仓库根真实契约，避免各测试类复制装配咒语。 */
public final class TestContracts {

    private static final Contracts INSTANCE = Contracts.load(Contracts.resolveDir());

    private TestContracts() {}

    public static Contracts instance() {
        return INSTANCE;
    }
}
