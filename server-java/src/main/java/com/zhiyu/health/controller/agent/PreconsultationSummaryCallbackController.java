package com.zhiyu.health.controller.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyu.health.service.chat.PreconsultationService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预问诊摘要异步回调（票 55 改造）：server-py 在 message/done 之后后台异步整理摘要，
 * 算出快照后回调本端点落草稿。摘要不再阻塞 message 事件，避免客户端输入框长时间锁死。
 *
 * <p>受 {@code AgentCallbackAuthFilter} 保护（/api/agent/** 需 X-Agent-Callback-Token）。
 * 落库委托 {@link PreconsultationService#applySummary}：主诉/现病史缺失保留上一版，
 * 草稿已并发提交 0 行静默返回--幂等旁路语义不变，回调失败不连坐对话流。
 */
@Validated
@RestController
@RequestMapping("/api/agent/preconsultation-drafts")
@RequiredArgsConstructor
@Slf4j
public class PreconsultationSummaryCallbackController {

    private final PreconsultationService preconsultationService;

    @PostMapping("/{draftId}/summary")
    public ResponseEntity<Void> applySummary(@PathVariable @Positive long draftId, @RequestBody JsonNode payload) {
        preconsultationService.applySummary(draftId, payload);
        return ResponseEntity.noContent().build();
    }
}
