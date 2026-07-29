package com.zhiyu.health.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.config.Contracts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 免责声明标注唯一入口（硬约束 1：一切 AI 产出必须携带）。文案取自跨栈契约
 * contracts/disclaimer.json，禁止在代码中另立字面量。存储层只存纯内容，
 * 标注一律在响应装配时经本组件挂载。
 */
@Service
@RequiredArgsConstructor
public class DisclaimerService {

    private final Contracts contracts;

    /** 权威文案原文。 */
    public String text() {
        return contracts.disclaimer().text();
    }

    /** 卡片字段挂载（SSE 出口兜底）：已带正确文案的幂等跳过，缺失或被篡改的覆盖。 */
    public void mount(ObjectNode card) {
        if (!text().equals(card.path("disclaimer").asText())) {
            card.put("disclaimer", text());
        }
    }

    /** 独立字段挂载语义：有内容才给文案，无内容返回 null（前端按字段有无渲染）。 */
    public String mountIfPresent(String content) {
        return content == null ? null : text();
    }
}
