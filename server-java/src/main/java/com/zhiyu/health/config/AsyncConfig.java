package com.zhiyu.health.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务配置（票 89，ADR-0036）。
 *
 * <p>本平台首个异步运营链路：知识文档上传后 @Async 后台跑完整链（MinIO 存原文 ->
 * 解析切分 -> 调 server-py embedding -> 写 chunk）。无 MQ/调度中间件（AGENTS.md 禁止引入），
 * 单实例拓扑下进程内有界线程池即可。
 *
 * <p>拒绝策略用 DiscardPolicy：队列满时新任务被静默丢弃，文档保持 PROCESSING 状态，
 * 由启动时孤儿扫描恢复为 FAILED。不 AbortPolicy（会抛异常到调用方线程，但 upload 已返回）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("knowledgeDocumentExecutor")
    public Executor knowledgeDocumentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("knowledge-doc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
