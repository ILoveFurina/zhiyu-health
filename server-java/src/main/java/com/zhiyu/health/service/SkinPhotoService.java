package com.zhiyu.health.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拍皮肤分析（票 15，ADR-0023）：复用 scenario 驱动的视觉管道，皮肤照片作为一等公民
 * 持久化到 MinIO，分析结果以 skin_analysis 卡片回落会话。
 *
 * <p>与报告解读的"即用即弃 + PROCESSING/SUCCEEDED 状态表"模型不同，皮肤场景是轻持久化：
 * 不建独立状态表，结果直接进 messages.content。图片走 MinIO 旁路持久化（image kind），
 * 写入失败降级为不留原图但分析正常完成（ADR-0023 硬约束）。
 *
 * <p>网络调用（interpretVision）故意不在事务内：图片消息已先行落库（MinIO 旁路），
 * 分析调用失败时不回滚已落库的图片消息，保证用户至少看到"我拍过什么"的回看价值。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkinPhotoService {

    private final ConversationService conversations;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final DisclaimerService disclaimers;
    private final MinioStorageService minioStorage;

    /**
     * 分析皮肤照片并回落会话。
     *
     * @param requestId 客户端幂等键；皮肤场景无独立状态表，当前仅用于审计追溯，
     *                  未来若需幂等可在此扩展查重。
     * @return 落库的皮肤分析卡片视图（含 conversation_id 供前端定位会话）
     */
    public SkinAnalysisView analyze(Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        validate(files);
        // 健康档案是分析上下文的前置（与报告解读一致），缺失则 422 引导用户先建档。
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(patientId);
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, "拍皮肤");

        // MinIO 旁路持久化：图片消息先行落库（image kind），失败静默降级不留原图（ADR-0023）。
        // 放在 interpretVision 之前：分析失败时图片仍留存，保证"我拍过什么"的回看价值。
        minioStorage.persistPhotosAndMessages(conversation.getId(), files);

        AgentClient.VisionResponse response;
        try {
            // 网络调用不在事务内，避免长事务占用连接与锁。
            response = agentClient.interpretVision(files, profile, "SKIN");
        } catch (AgentClient.VisionAgentException e) {
            // 分析失败时回退一句引导就医的兜底话术（硬约束 1/2 + 票 15 异常描述兜底）。
            appendFallbackCard(conversation.getId(), e);
            throw new ApiException(e.status(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            appendFallbackCard(conversation.getId(), null);
            throw new ApiException(502, "皮肤分析服务暂不可用");
        }

        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(response.result());
        } catch (Exception e) {
            appendFallbackCard(conversation.getId(), null);
            throw new ApiException(502, "皮肤分析结果损坏");
        }
        ObjectNode card = objectMapper.createObjectNode();
        card.set("result", response.result());
        card.put("disclaimer", disclaimers.text());
        // skin_analysis 卡片作为 AI 消息回落会话；content 存卡片 JSON 供历史回放渲染。
        conversations.appendMessage(
                conversation.getId(), "assistant", card.toString(), Message.KIND_SKIN_ANALYSIS, null, null, null);
        return new SkinAnalysisView(conversation.getId(), response.result(), disclaimers.text());
    }

    private void validate(List<MultipartFile> files) {
        Contracts.UploadLimits limits = contracts.uploadLimits();
        if (files.size() < limits.minFiles() || files.size() > limits.maxFiles()) {
            throw new ApiException(422, "请上传 1-5 张皮肤照片");
        }
        long total = 0;
        for (MultipartFile file : files) {
            total += file.getSize();
            if (file.isEmpty()
                    || file.getSize() > limits.maxFileBytes()
                    || !PhotoFileTypes.isAllowedImage(file, limits.imageTypes())) {
                throw new ApiException(422, "仅支持规定大小的 JPEG 或 PNG 皮肤照片");
            }
        }
        if (total > limits.maxTotalBytes()) {
            throw new ApiException(422, "皮肤照片总量不能超过 20MB");
        }
    }

    /**
     * 分析失败的就医兜底话术（票 15）：落一条 skin_analysis 卡片引导用户就医，
     * 保证异常时用户仍能看到有意义的指引而非空会话。硬约束 1 免责声明始终挂载。
     */
    private void appendFallbackCard(Long conversationId, AgentClient.VisionAgentException error) {
        ObjectNode fallback = objectMapper.createObjectNode();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("skin_type", "未能完成分析");
        result.putArray("findings");
        String hint = error != null && "VISION_SKIN_SCOPE_UNSUPPORTED".equals(error.code())
                ? "请上传清晰的皮肤照片，暂不支持医学影像或报告诊断。"
                : "皮肤分析暂不可用，如皮肤有明显不适请及时就医。";
        result.put("care_summary", hint);
        result.put("need_doctor", true);
        fallback.set("result", result);
        fallback.put("disclaimer", disclaimers.text());
        conversations.appendMessage(
                conversationId, "assistant", fallback.toString(), Message.KIND_SKIN_ANALYSIS, null, null, null);
    }

    public record SkinAnalysisView(
            @JsonProperty("conversation_id") Long conversationId, JsonNode result, String disclaimer) {}
}
