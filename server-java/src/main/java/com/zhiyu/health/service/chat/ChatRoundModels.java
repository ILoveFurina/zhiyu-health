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
            Long preconsultationDraftId) {}

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
