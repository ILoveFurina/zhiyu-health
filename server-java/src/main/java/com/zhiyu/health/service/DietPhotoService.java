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
 * 拍饮食分析（票 16，照搬 15 皮肤模板）：复用 scenario 驱动的视觉管道，饮食照片作为
 * 一等公民持久化到 MinIO，分析结果以 diet_analysis 卡片回落会话。
 *
 * <p>差异化点（见票 16）：结合健康档案过敏史给出个性化一句提醒。档案注入由
 * interpreter 的 _content_blocks 统一完成（scenario 无关），饮食 prompt 收到过敏史后在
 * 识别出食材后比对过敏原，命中则产出风险提示。无激活档案时正常分析，仅缺个性化提醒句。
 *
 * <p>网络调用（interpretVision）故意不在事务内：图片消息已先行落库（MinIO 旁路），
 * 分析调用失败时不回滚已落库的图片消息，保证用户至少看到"我拍过什么"的回看价值。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DietPhotoService {

    private final ConversationService conversations;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final DisclaimerService disclaimers;
    private final MinioStorageService minioStorage;

    /**
     * 分析饮食照片并回落会话。
     *
     * @param requestId 客户端幂等键；饮食场景无独立状态表，当前仅用于审计追溯，
     *                  未来若需幂等可在此扩展查重。
     * @return 落库的饮食分析卡片视图（含 conversation_id 供前端定位会话）
     */
    public DietAnalysisView analyze(Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        validate(files);
        // 饮食场景差异化（票 16）：无激活档案时仍正常分析，仅缺个性化提醒句。
        // 有档案时 prompt 注入过敏原供 LLM 比对食材，命中则产出风险提示；agentContext 返回 null 时透传。
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(patientId);
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, "拍饮食");

        // MinIO 旁路持久化：图片消息先行落库（image kind），失败静默降级不留原图（ADR-0023）。
        // 放在 interpretVision 之前：分析失败时图片仍留存，保证"我拍过什么"的回看价值。
        minioStorage.persistPhotosAndMessages(conversation.getId(), files);

        AgentClient.VisionResponse response;
        try {
            // 网络调用不在事务内，避免长事务占用连接与锁。
            response = agentClient.interpretVision(files, profile, "DIET");
        } catch (AgentClient.VisionAgentException e) {
            // 分析失败时回退一句引导就医/咨询营养师的兜底话术（硬约束 1/2 + 票 16 异常描述兜底）。
            appendFallbackCard(conversation.getId(), e);
            throw new ApiException(e.status(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            appendFallbackCard(conversation.getId(), null);
            throw new ApiException(502, "饮食分析服务暂不可用");
        }

        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(response.result());
        } catch (Exception e) {
            appendFallbackCard(conversation.getId(), null);
            throw new ApiException(502, "饮食分析结果损坏");
        }
        ObjectNode card = objectMapper.createObjectNode();
        card.set("result", response.result());
        card.put("disclaimer", disclaimers.text());
        // diet_analysis 卡片作为 AI 消息回落会话；content 存卡片 JSON 供历史回放渲染。
        conversations.appendMessage(
                conversation.getId(), "assistant", card.toString(), Message.KIND_DIET_ANALYSIS, null, null, null);
        return new DietAnalysisView(conversation.getId(), response.result(), disclaimers.text());
    }

    private void validate(List<MultipartFile> files) {
        Contracts.UploadLimits limits = contracts.uploadLimits();
        if (files.size() < limits.minFiles() || files.size() > limits.maxFiles()) {
            throw new ApiException(422, "请上传 1-5 张饮食照片");
        }
        long total = 0;
        for (MultipartFile file : files) {
            total += file.getSize();
            if (file.isEmpty()
                    || file.getSize() > limits.maxFileBytes()
                    || !limits.imageTypes().contains(file.getContentType())) {
                throw new ApiException(422, "仅支持规定大小的 JPEG 或 PNG 饮食照片");
            }
        }
        if (total > limits.maxTotalBytes()) {
            throw new ApiException(422, "饮食照片总量不能超过 20MB");
        }
    }

    /**
     * 分析失败的就医兜底话术（票 16）：落一条 diet_analysis 卡片引导用户就医/咨询营养师，
     * 保证异常时用户仍能看到有意义的指引而非空会话。硬约束 1 免责声明始终挂载。
     */
    private void appendFallbackCard(Long conversationId, AgentClient.VisionAgentException error) {
        ObjectNode fallback = objectMapper.createObjectNode();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("meal_type", "未能完成分析");
        result.putArray("foods");
        result.put("estimated_calories", "无法估量");
        result.put("nutrition_summary", "未能完成分析");
        String hint = error != null && "VISION_DIET_SCOPE_UNSUPPORTED".equals(error.code())
                ? "请上传清晰的饮食照片，暂不支持医学影像或报告诊断。"
                : "饮食分析暂不可用，如有特殊饮食需求请咨询医生或营养师。";
        result.put("diet_advice", hint);
        result.put("personal_tip", "");
        result.put("need_doctor", true);
        fallback.set("result", result);
        fallback.put("disclaimer", disclaimers.text());
        conversations.appendMessage(
                conversationId, "assistant", fallback.toString(), Message.KIND_DIET_ANALYSIS, null, null, null);
    }

    public record DietAnalysisView(
            @JsonProperty("conversation_id") Long conversationId, JsonNode result, String disclaimer) {}
}
