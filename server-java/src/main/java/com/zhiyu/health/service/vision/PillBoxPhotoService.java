package com.zhiyu.health.service.vision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.Conversation;
import com.zhiyu.health.entity.chat.Message;
import com.zhiyu.health.service.chat.ConversationService;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拍药盒（票 51，ADR-0028）：vision 退化为纯 OCR 提名器，识别药名即返回，
 * 说明书由客户端经 chat 信封 medication_name 走通用药品知识流（流式文本）。
 *
 * <p>票 14（ADR-0025）的 C 端个性化安全部分（双列查 + 规则引擎 + medication_info/medication_safety
 * 双出口卡片）已被票 51 删除：说明书内容来自 LLM 通用语料，不查 medications 表、不做禁忌判定
 * （禁忌仅留 B 端开方链路，ADR-0016）。链路：
 * <pre>
 * 拍照 -> MinIO 旁路持久化 -> server-py vision 提候选药名(PILL_BOX)
 *      -> 响应 {request_id, conversation_id, recognized, drug_names[], hint?}
 * </pre>
 *
 * <p>网络调用（interpretVision 与 MinIO 上传）故意不在事务内且相互并行（票 51）：图片消息
 * 经旁路独立事务落库，分析调用失败时不回滚已落库的图片消息，保证用户至少看到"我拍过什么"的回看价值。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PillBoxPhotoService {

    private final ConversationService conversations;
    private final AgentClient agentClient;
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final MinioStorageService minioStorage;

    /**
     * 识别药盒照片上的药名并回落会话图片。
     *
     * @param requestId 客户端幂等键；药盒场景无独立状态表，当前仅用于审计追溯。
     * @return OCR 提名视图；vision 未识别药名时 recognized=false 且携带 hint 引导文案
     */
    public PillBoxPhotoView analyze(Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        validate(files);
        // 档案注入仍透传给 prompt（药盒场景无差异化需求，但保持一致注入点）。
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(patientId);
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, "拍药盒");

        // MinIO 旁路持久化与 vision 调用并行（票 51）：两个网络调用重叠，端到端取较大者而非求和。
        // best-effort 语义不变（ADR-0023）：图片消息独立事务落库、单项失败静默降级不留原图；
        // persist 的未捕获异常仍在下方 join 处原样上抛，与串行版失败语义一致。
        java.util.concurrent.CompletableFuture<Void> persist = java.util.concurrent.CompletableFuture.runAsync(
                () -> minioStorage.persistPhotosAndMessages(conversation.getId(), files));

        AgentClient.VisionResponse response;
        try {
            response = agentClient.interpretVision(files, profile, "PILL_BOX");
        } catch (AgentClient.VisionAgentException e) {
            appendFallbackCard(conversation.getId(), e);
            throw new ApiException(e.status(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            appendFallbackCard(conversation.getId(), null);
            throw new ApiException(502, "药盒识别服务暂不可用");
        }
        // vision 已出结果，persist 通常也已完成；join 只为保持"返回前图片落库完毕"的串行语义
        persist.join();

        // vision 只提候选药名：从 result.candidates[].name 提取药名列表。
        List<String> candidateNames = extractCandidateNames(response.result());
        if (candidateNames.isEmpty()) {
            // vision 未识别到任何药名（多药混拍/文字模糊）：落一条 text 消息引导用户重拍或输入药名。
            String hint = "未能识别药盒上的药名，请重拍清晰的药盒照片或直接输入药名。";
            conversations.appendMessage(conversation.getId(), "assistant", hint, Message.KIND_TEXT, null, null, null);
            return new PillBoxPhotoView(requestId, conversation.getId(), false, List.of(), hint);
        }
        return new PillBoxPhotoView(requestId, conversation.getId(), true, candidateNames, null);
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

    private void validate(List<MultipartFile> files) {
        new PhotoUploadValidator(contracts).validate(files, "药盒");
    }

    /**
     * 分析失败的兜底话术：落一条 text 消息引导用户重拍或咨询药师。
     * 硬约束 1 通用免责始终挂载。
     */
    private void appendFallbackCard(Long conversationId, AgentClient.VisionAgentException error) {
        String hint = error != null && "VISION_PILL_BOX_SCOPE_UNSUPPORTED".equals(error.code())
                ? "请上传清晰的药盒照片，暂不支持医学影像或报告诊断。"
                : "药盒识别暂不可用，请重拍或直接输入药名，也可咨询医生或药师。";
        conversations.appendMessage(conversationId, "assistant", hint, Message.KIND_TEXT, null, null, null);
    }

    /**
     * OCR 提名视图（票 51）：不再回卡片。hint 仅在 recognized=false 时携带（未识别的引导文案）。
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
