package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 知识源现场切换（票 25，ADR-0021）：运行时状态存 Redis 全局单键 {@code demo:knowledge_source}。
 *
 * B 端 {@code PUT /api/b/demo/knowledge-source}（admin）写键；C 端对话请求未显式带
 * {@code knowledge_source} 时由 {@link ChatRoundService#agentBody} 读键补位透传，
 * 优先级"请求 > 全局键 > scenario 默认"。server-py 完全不感知开关存在。
 *
 * 全局单键、串行切换（B 端切一次、C 端发新对话看效果），不做并行三路对比。
 * 开关状态非持久：Redis 重启或键过期后回到默认 none。
 */
@Service
@RequiredArgsConstructor
public class DemoKnowledgeSourceService {

    private final StringRedisTemplate redis;
    private final Contracts contracts;

    /** 读全局键；缺失返回契约默认 none。 */
    public String current() {
        String value = redis.opsForValue().get(contracts.demoArsenal().knowledgeSourceRedisKey());
        if (value == null || value.isBlank()) {
            return contracts.demoArsenal().knowledgeSourceDefault();
        }
        return value;
    }

    /** 写全局键；非法值 400。 */
    public void update(String knowledgeSource) {
        if (knowledgeSource == null
                || !contracts.demoArsenal().knowledgeSourceValues().contains(knowledgeSource)) {
            throw new ApiException(400, "不支持的知识源值");
        }
        redis.opsForValue().set(contracts.demoArsenal().knowledgeSourceRedisKey(), knowledgeSource);
    }
}
