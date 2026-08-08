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

/** 皮肤照片场景入口；共享的上传、MinIO、Agent、降级和会话落库顺序由视觉管道负责。 */
@Service
public class SkinPhotoService {

    private final ConversationVisionPipeline pipeline;

    public SkinPhotoService(
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

    public SkinAnalysisView analyze(Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        ConversationVisionPipeline.Outcome outcome =
                pipeline.analyze(patientId, conversationId, files, PhotoAnalysisScenario.SKIN);
        return new SkinAnalysisView(outcome.conversationId(), outcome.result(), outcome.disclaimer());
    }

    public record SkinAnalysisView(
            @JsonProperty("conversation_id") Long conversationId, JsonNode result, String disclaimer) {}
}
