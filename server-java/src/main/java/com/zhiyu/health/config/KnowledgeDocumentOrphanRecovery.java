package com.zhiyu.health.config;

import com.zhiyu.health.service.knowledge.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时孤儿文档恢复（票 89，ADR-0036 决策第 6 点）。
 *
 * <p>进程崩溃/重启时 PROCESSING 文档会卡死。启动时扫描 status=PROCESSING 且
 * processing_started_at 超过 orphan_timeout_seconds（600s）的记录，标 FAILED(ORPHANED)。
 * 单实例拓扑下启动时一次性扫描即可，无需心跳/看门狗中间件。
 *
 * <p>Order(100)：在 StaffUserSeed 之后执行，避免与其他启动任务竞争。
 */
@Component
@Order(100)
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentOrphanRecovery implements ApplicationRunner {

    private final KnowledgeDocumentService knowledgeDocumentService;

    @Override
    public void run(ApplicationArguments args) {
        int recovered = knowledgeDocumentService.recoverOrphanedProcessing();
        if (recovered > 0) {
            log.info("孤儿文档恢复：标记 {} 个 PROCESSING 文档为 FAILED(ORPHANED)", recovered);
        }
    }
}
