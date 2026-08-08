package com.zhiyu.health.service.vision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.Message;
import com.zhiyu.health.service.chat.ConversationService;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拍药盒（ADR-0028）：vision 仅作 OCR 提名器，识别药名即返回，
 * 说明书由客户端经 chat 信封 medication_name 走通用药品知识流（流式文本）。
 *
 * <p>C 端不做个性化用药决策：说明书内容来自通用药品知识流，不查业务药品表、不做禁忌判定；
 * 禁忌只留在 B 端开方链路（ADR-0016）。链路：
 * <pre>
 * 拍照 -> MinIO 旁路持久化 -> server-py vision 提候选药名(PILL_BOX)
 *      -> 响应 {request_id, conversation_id, recognized, drug_names[], hint?}
 * </pre>
 *
 * <p>MinIO 与 Agent 网络调用不放进数据库事务。图片消息先经旁路独立落库，分析失败不回滚原图，
 * 保证用户仍能回看自己上传过什么。
 */
@Service
@Slf4j
public class PillBoxPhotoService {

    private final ConversationService conversations;
    private final ConversationVisionPipeline pipeline;

    @Autowired
    public PillBoxPhotoService(
            ConversationService conversations,
            AgentClient agentClient,
            Contracts contracts,
            HealthProfileService healthProfiles,
            MinioStorageService minioStorage,
            ObjectMapper objectMapper,
            DisclaimerService disclaimers) {
        this.conversations = conversations;
        this.pipeline = new ConversationVisionPipeline(
                conversations, agentClient, objectMapper, contracts, healthProfiles, disclaimers, minioStorage);
    }

    /** 保留既有单测和模块内直接构造入口；生产注入使用完整构造器。 */
    public PillBoxPhotoService(
            ConversationService conversations,
            AgentClient agentClient,
            Contracts contracts,
            HealthProfileService healthProfiles,
            MinioStorageService minioStorage) {
        this(
                conversations,
                agentClient,
                contracts,
                healthProfiles,
                minioStorage,
                new ObjectMapper(),
                new DisclaimerService(contracts));
    }

    /**
     * 识别药盒照片上的药名并回落会话图片。
     *
     * @param requestId 客户端幂等键；药盒场景无独立状态表，当前仅用于审计追溯。
     * @return OCR 提名视图；vision 未识别药名时 recognized=false 且携带 hint 引导文案
     */
    public PillBoxPhotoView analyze(Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        ConversationVisionPipeline.RawOutcome raw = pipeline.interpret(
                patientId, conversationId, files, PhotoAnalysisScenario.PILL_BOX, this::appendFallbackCard);

        // vision 只提候选药名：从 result.candidates[].name 提取药名列表。
        List<String> candidateNames = extractCandidateNames(raw.response().result());
        if (candidateNames.isEmpty()) {
            // vision 未识别到任何药名（多药混拍/文字模糊）：落一条 text 消息引导用户重拍或输入药名。
            String hint = "未能识别药盒上的药名，请重拍清晰的药盒照片或直接输入药名。";
            conversations.appendMessage(
                    raw.conversation().getId(), "assistant", hint, Message.KIND_TEXT, null, null, null);
            return new PillBoxPhotoView(requestId, raw.conversation().getId(), false, List.of(), hint);
        }
        return new PillBoxPhotoView(requestId, raw.conversation().getId(), true, candidateNames, null);
    }

    /** 从 PILL_BOX vision 结果提取候选药名列表。 */
    private List<String> extractCandidateNames(JsonNode result) {
        List<String> names = new ArrayList<>();
        if (result == null) {
            return names;
        }
        JsonNode candidates = result.path("candidates");
        for (JsonNode candidate : candidates) {
            String name = candidate.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /**
     * 分析失败的兜底话术：落一条 text 消息引导用户重拍或咨询药师。
     * 硬约束 1 通用免责始终挂载。
     */
    private void appendFallbackCard(
            long conversationId, PhotoAnalysisScenario scenario, AgentClient.VisionAgentException error) {
        String hint = error != null && "VISION_PILL_BOX_SCOPE_UNSUPPORTED".equals(error.code())
                ? "请上传清晰的药盒照片，暂不支持医学影像或报告诊断。"
                : "药盒识别暂不可用，请重拍或直接输入药名，也可咨询医生或药师。";
        conversations.appendMessage(conversationId, "assistant", hint, Message.KIND_TEXT, null, null, null);
    }

    /**
     * OCR 提名视图：不再回卡片。hint 仅在 recognized=false 时携带（未识别的引导文案）。
     * 客户端按 drug_names[0] 经 chat 信封 medication_name 请求通用药品说明书流。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PillBoxPhotoView(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("conversation_id") Long conversationId,
            boolean recognized,
            @JsonProperty("drug_names") List<String> drugNames,
            String hint) {}
}
