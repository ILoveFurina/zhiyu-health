package com.zhiyu.health.service.vision;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.chat.ConversationService;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 饮食照片场景入口；健康档案仍由公共管道注入，用于过敏史个性化提醒。 */
@Service
public class DietPhotoService {

    private final ConversationVisionPipeline pipeline;

    public DietPhotoService(
            ConversationService conversations,
            AgentClient agentClient,
            ObjectMapper objectMapper,
            Contracts contracts,
            HealthProfileService healthProfiles,
            DisclaimerService disclaimers,
            MinioStorageService minioStorage) {
        this.pipeline = new ConversationVisionPipeline(
                conversations, agentClient, objectMapper, contracts, healthProfiles, disclaimers, minioStorage);
    }

    public DietAnalysisView analyze(Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        ConversationVisionPipeline.Outcome outcome =
                pipeline.analyze(patientId, conversationId, files, PhotoAnalysisScenario.DIET);
        return new DietAnalysisView(outcome.conversationId(), outcome.result(), outcome.disclaimer());
    }

    public record DietAnalysisView(
            @JsonProperty("conversation_id") Long conversationId, JsonNode result, String disclaimer) {}
}
