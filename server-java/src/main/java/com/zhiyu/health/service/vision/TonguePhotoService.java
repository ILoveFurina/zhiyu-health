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

/** 舌苔场景入口；在通用免责声明之外保留中医专属免责声明。 */
@Service
public class TonguePhotoService {

    private final ConversationVisionPipeline pipeline;

    public TonguePhotoService(
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

    public TongueAnalysisView analyze(
            Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        ConversationVisionPipeline.Outcome outcome =
                pipeline.analyze(patientId, conversationId, files, PhotoAnalysisScenario.TONGUE);
        return new TongueAnalysisView(
                outcome.conversationId(), outcome.result(), outcome.disclaimer(), outcome.tcmDisclaimer());
    }

    public record TongueAnalysisView(
            @JsonProperty("conversation_id") Long conversationId,
            JsonNode result,
            String disclaimer,
            @JsonProperty("tcm_disclaimer") String tcmDisclaimer) {}
}
