package com.zhiyu.health.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.service.MedicationLookupService.MedicationLookupView;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拍药盒（票 14，ADR-0025）：照搬 15/16/17 拍照管道模板，但视觉只提候选药名。
 *
 * <p>与 15/16/17"视觉直接出分析卡片"根本不同：14 的 vision 退化为 OCR 提名器，
 * 药品匹配与禁忌判定全在 server-java 完成。链路：
 * <pre>
 * 拍照 -> MinIO 旁路持久化 -> server-py vision 提候选药名(PILL_BOX)
 *      -> MedicationLookupService 双列查 + 规则引擎 -> medication_info/medication_safety 双出口
 * </pre>
 *
 * <p>网络调用（interpretVision）故意不在事务内：图片消息已先行落库（MinIO 旁路），
 * 分析调用失败时不回滚已落库的图片消息，保证用户至少看到"我拍过什么"的回看价值。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PillBoxPhotoService {

    private final ConversationService conversations;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final MinioStorageService minioStorage;
    private final MedicationLookupService medicationLookup;

    /**
     * 分析药盒照片并回落会话。
     *
     * @param requestId 客户端幂等键；药盒场景无独立状态表，当前仅用于审计追溯。
     * @return 双出口视图（medication_info + medication_safety）；vision 未识别药名时 notFound=true
     */
    public MedicationLookupView analyze(
            Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        validate(files);
        // 档案注入仍透传给 prompt（药盒场景无差异化需求，但保持一致注入点）。
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(patientId);
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, "拍药盒");

        // MinIO 旁路持久化：图片消息先行落库（image kind），失败静默降级不留原图（ADR-0023）。
        minioStorage.persistPhotosAndMessages(conversation.getId(), files);

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

        // vision 只提候选药名：从 result.candidates[].name 提取药名列表。
        List<String> candidateNames = extractCandidateNames(response.result());
        if (candidateNames.isEmpty()) {
            // vision 未识别到任何药名（多药混拍/文字模糊）：落一条 text 消息引导用户重拍或使用查药品入口。
            String hint = "未能识别药盒上的药名，请重拍清晰的药盒照片或使用「查药品」入口输入药名。";
            conversations.appendMessage(conversation.getId(), "assistant", hint, Message.KIND_TEXT, null, null, null);
            return new MedicationLookupView(
                    conversation.getId(),
                    null,
                    null,
                    true,
                    hint,
                    null,
                    contracts.disclaimer().text());
        }

        // 双出口：委托 MedicationLookupService 查询 + 规则引擎 + 双卡片回落。
        // 传入已建会话 id（非 null），确保图片与双出口卡片落入同一会话，不会因 null 重复建会话。
        return medicationLookup.lookupAndAppend(patientId, conversation.getId(), "拍药盒", candidateNames);
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
        Contracts.UploadLimits limits = contracts.uploadLimits();
        if (files.size() < limits.minFiles() || files.size() > limits.maxFiles()) {
            throw new ApiException(422, "请上传 1-5 张药盒照片");
        }
        long total = 0;
        for (MultipartFile file : files) {
            total += file.getSize();
            if (file.isEmpty()
                    || file.getSize() > limits.maxFileBytes()
                    || !limits.imageTypes().contains(file.getContentType())) {
                throw new ApiException(422, "仅支持规定大小的 JPEG 或 PNG 药盒照片");
            }
        }
        if (total > limits.maxTotalBytes()) {
            throw new ApiException(422, "药盒照片总量不能超过 20MB");
        }
    }

    /**
     * 分析失败的兜底话术：落一条 text 消息引导用户重拍或咨询药师。
     * 硬约束 1 通用免责始终挂载。
     */
    private void appendFallbackCard(Long conversationId, AgentClient.VisionAgentException error) {
        String hint = error != null && "VISION_PILL_BOX_SCOPE_UNSUPPORTED".equals(error.code())
                ? "请上传清晰的药盒照片，暂不支持医学影像或报告诊断。"
                : "药盒识别暂不可用，请重拍或使用「查药品」入口输入药名，也可咨询医生或药师。";
        conversations.appendMessage(conversationId, "assistant", hint, Message.KIND_TEXT, null, null, null);
    }
}
