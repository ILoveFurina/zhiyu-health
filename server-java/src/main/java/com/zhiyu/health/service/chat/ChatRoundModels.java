package com.zhiyu.health.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

/** 对话入口命令与事件视图；运行态和编排细节不暴露给 controller。 */
public final class ChatRoundModels {
    private ChatRoundModels() {}

    public record Command(
            Long patientId,
            String requestId,
            Long conversationId,
            String content,
            String effort,
            String scenario,
            String knowledgeSource,
            Double longitude,
            Double latitude,
            Long retryStandardDepartmentId,
            // 票 55：预问诊草稿标识；非空时服务端校验归属/状态并强制 preconsultation 场景
            Long preconsultationDraftId,
            // 票 80：处方选择卡点选回传的所选处方 ID，仅透传给 server-py，归属校验延后到 prepare
            Long prescriptionId) {}

    /** C 端通用药品知识命令，不携带健康档案或个性化用药参数。 */
    public record MedicationCommand(Long patientId, String requestId, Long conversationId, String drugName) {}

    public record Event(String event, JsonNode data) {}

    public record Handle(String requestId, Long conversationId, String status, Flux<Event> events) {}

    public static class RoundFailedException extends RuntimeException {
        public RoundFailedException(String message) {
            super(message);
        }

        public RoundFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
