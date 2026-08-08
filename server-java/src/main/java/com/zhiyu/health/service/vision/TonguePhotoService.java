package com.zhiyu.health.service.vision;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.Conversation;
import com.zhiyu.health.entity.chat.Message;
import com.zhiyu.health.service.chat.ConversationService;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拍舌苔中医辨证（票 17，照搬 15/16 拍照模板，ADR-0024 合规边界）：复用 scenario 驱动的
 * 视觉管道，舌苔照片作为一等公民持久化到 MinIO，分析结果以 tongue_analysis 卡片回落会话。
 *
 * <p>三条合规差异化边界（ADR-0024）：
 * <ol>
 *   <li>调理建议只讲方向，不出药材/方剂/剂量。卡片只承载体质辨识 + 调理方向 + 通用饮食原则，
 *       禁药材字段由 server-py prompt 前置约束，本服务不额外校验（LLM 产出已受 prompt 约束）。
 *   <li>舌诊卡片叠加通用免责 + 中医专属免责两条（其他 AI 产出一律只取通用）。
 *   <li>舌象急症只做软兜底：分析失败或 need_doctor 时引导就医，不回流 RedFlagRuleEngine。
 * </ol>
 *
 * <p>网络调用（interpretVision）故意不在事务内：图片消息已先行落库（MinIO 旁路），
 * 分析调用失败时不回滚已落库的图片消息，保证用户至少看到"我拍过什么"的回看价值。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TonguePhotoService {

    private final ConversationService conversations;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final DisclaimerService disclaimers;
    private final MinioStorageService minioStorage;

    /**
     * 分析舌苔照片并回落会话。
     *
     * @param requestId 客户端幂等键；舌苔场景无独立状态表，当前仅用于审计追溯。
     * @return 落库的舌苔辨证卡片视图（含 conversation_id 供前端定位会话，tcm_disclaimer 为中医专属免责）
     */
    public TongueAnalysisView analyze(
            Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        validate(files);
        // 舌苔场景无档案差异化需求：档案注入仍透传给 prompt（体质辨证可参考年龄/性别），无档案正常分析。
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(patientId);
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, "拍舌苔");

        // MinIO 旁路持久化：图片消息先行落库（image kind），失败静默降级不留原图（ADR-0023）。
        // 放在 interpretVision 之前：分析失败时图片仍留存，保证"我拍过什么"的回看价值。
        minioStorage.persistPhotosAndMessages(conversation.getId(), files);

        AgentClient.VisionResponse response;
        try {
            // 网络调用不在事务内，避免长事务占用连接与锁。
            response = agentClient.interpretVision(files, profile, "TONGUE");
        } catch (AgentClient.VisionAgentException e) {
            // 分析失败时回退一句引导就医的兜底话术（ADR-0024 第 3 条软兜底 + 硬约束 1/2）。
            appendFallbackCard(conversation.getId(), e);
            throw new ApiException(e.status(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            appendFallbackCard(conversation.getId(), null);
            throw new ApiException(502, "舌苔辨证服务暂不可用");
        }

        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(response.result());
        } catch (Exception e) {
            appendFallbackCard(conversation.getId(), null);
            throw new ApiException(502, "舌苔辨证结果损坏");
        }
        // ADR-0024 第 2 条：舌诊卡片叠加通用免责 + 中医专属免责两条。
        // 双栈同步：server-py 在 VisionResponse.tcm_disclaimer 注入，此处出口兜底--
        // 若 server-py 未带（旧版兼容或非舌诊路径）则用本地契约 tcmText() 兜底。
        String tcmDisclaimer = response.tcmDisclaimer() != null ? response.tcmDisclaimer() : disclaimers.tcmText();
        ObjectNode card = objectMapper.createObjectNode();
        card.set("result", response.result());
        card.put("disclaimer", disclaimers.text());
        card.put("tcm_disclaimer", tcmDisclaimer);
        // tongue_analysis 卡片作为 AI 消息回落会话；content 存卡片 JSON 供历史回放渲染。
        conversations.appendMessage(
                conversation.getId(), "assistant", card.toString(), Message.KIND_TONGUE_ANALYSIS, null, null, null);
        return new TongueAnalysisView(conversation.getId(), response.result(), disclaimers.text(), tcmDisclaimer);
    }

    private void validate(List<MultipartFile> files) {
        Contracts.UploadLimits limits = contracts.uploadLimits();
        if (files.size() < limits.minFiles() || files.size() > limits.maxFiles()) {
            throw new ApiException(422, "请上传 1-5 张舌苔照片");
        }
        long total = 0;
        for (MultipartFile file : files) {
            total += file.getSize();
            if (file.isEmpty()
                    || file.getSize() > limits.maxFileBytes()
                    || !PhotoFileTypes.isAllowedImage(file, limits.imageTypes())) {
                throw new ApiException(422, "仅支持规定大小的 JPEG 或 PNG 舌苔照片");
            }
        }
        if (total > limits.maxTotalBytes()) {
            throw new ApiException(422, "舌苔照片总量不能超过 20MB");
        }
    }

    /**
     * 分析失败的就医兜底话术（ADR-0024 第 3 条软兜底）：落一条 tongue_analysis 卡片引导用户就医，
     * 保证异常时用户仍能看到有意义的指引而非空会话。硬约束 1 通用免责 + 中医专属免责始终挂载。
     */
    private void appendFallbackCard(Long conversationId, AgentClient.VisionAgentException error) {
        ObjectNode fallback = objectMapper.createObjectNode();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("constitution", "未能完成辨证");
        result.put("tongue_features", "未能完成分析");
        result.put("care_direction", "未能完成分析");
        result.put("diet_principle", "未能完成分析");
        String hint = error != null && "VISION_TONGUE_SCOPE_UNSUPPORTED".equals(error.code())
                ? "请上传清晰的舌苔照片，暂不支持医学影像或报告诊断。"
                : "舌苔辨证暂不可用，如舌象明显异常请尽快就医，由中医面诊确认。";
        result.put("urgency_hint", hint);
        result.put("need_doctor", true);
        fallback.set("result", result);
        fallback.put("disclaimer", disclaimers.text());
        fallback.put("tcm_disclaimer", disclaimers.tcmText());
        conversations.appendMessage(
                conversationId, "assistant", fallback.toString(), Message.KIND_TONGUE_ANALYSIS, null, null, null);
    }

    public record TongueAnalysisView(
            @JsonProperty("conversation_id") Long conversationId,
            JsonNode result,
            String disclaimer,
            @JsonProperty("tcm_disclaimer") String tcmDisclaimer) {}
}
