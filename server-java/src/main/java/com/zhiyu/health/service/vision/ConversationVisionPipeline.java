package com.zhiyu.health.service.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.Conversation;
import com.zhiyu.health.service.chat.ConversationService;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 会话型视觉分析的公共流程。
 *
 * <p>原图消息必须先于 Agent 调用落库：Agent 失败时不回滚 MinIO 旁路结果，用户仍能回看自己上传过什么。
 * Agent 网络调用不放进数据库事务，避免慢请求长时间占用连接和锁。
 */
final class ConversationVisionPipeline {

    private final ConversationService conversations;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final HealthProfileService healthProfiles;
    private final DisclaimerService disclaimers;
    private final MinioStorageService minioStorage;
    private final PhotoUploadValidator uploads;

    ConversationVisionPipeline(
            ConversationService conversations,
            AgentClient agentClient,
            ObjectMapper objectMapper,
            Contracts contracts,
            HealthProfileService healthProfiles,
            DisclaimerService disclaimers,
            MinioStorageService minioStorage) {
        this.conversations = conversations;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.healthProfiles = healthProfiles;
        this.disclaimers = disclaimers;
        this.minioStorage = minioStorage;
        this.uploads = new PhotoUploadValidator(contracts);
    }

    Outcome analyze(long patientId, Long conversationId, List<MultipartFile> files, PhotoAnalysisScenario scenario) {
        uploads.validate(files, scenario.photoName());
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(patientId);
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, scenario.title());

        // 旁路持久化失败由 MinioStorageService 自己降级；这里不让对象存储决定分析事务的成败。
        minioStorage.persistPhotosAndMessages(conversation.getId(), files);

        AgentClient.VisionResponse response;
        try {
            response = agentClient.interpretVision(files, profile, scenario.agentScenario());
            // 先验证结果确实可序列化，再写会话卡片，避免留下无法回放的半成品消息。
            objectMapper.writeValueAsString(response.result());
        } catch (AgentClient.VisionAgentException e) {
            appendFallback(conversation.getId(), scenario, e);
            throw new ApiException(e.status(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            appendFallback(conversation.getId(), scenario, null);
            throw new ApiException(502, scenario.unavailableMessage());
        } catch (Exception e) {
            appendFallback(conversation.getId(), scenario, null);
            throw new ApiException(502, scenario.corruptMessage());
        }

        String tcmDisclaimer = scenario.tcm()
                ? (response.tcmDisclaimer() == null ? disclaimers.tcmText() : response.tcmDisclaimer())
                : null;
        ObjectNode card = objectMapper.createObjectNode();
        card.set("result", response.result());
        card.put("disclaimer", disclaimers.text());
        if (tcmDisclaimer != null) {
            card.put("tcm_disclaimer", tcmDisclaimer);
        }
        conversations.appendMessage(
                conversation.getId(), "assistant", card.toString(), scenario.messageKind(), null, null, null);
        return new Outcome(conversation.getId(), response.result(), disclaimers.text(), tcmDisclaimer);
    }

    private void appendFallback(
            long conversationId, PhotoAnalysisScenario scenario, AgentClient.VisionAgentException error) {
        ObjectNode card = objectMapper.createObjectNode();
        card.set("result", scenario.fallback(objectMapper, error));
        card.put("disclaimer", disclaimers.text());
        if (scenario.tcm()) {
            card.put("tcm_disclaimer", disclaimers.tcmText());
        }
        conversations.appendMessage(
                conversationId, "assistant", card.toString(), scenario.messageKind(), null, null, null);
    }

    record Outcome(Long conversationId, JsonNode result, String disclaimer, String tcmDisclaimer) {}
}
