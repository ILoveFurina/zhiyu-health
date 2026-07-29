package com.zhiyu.health.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.ReportInterpretation;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 报告解读业务编排；外部模型调用发生在两个短事务之间。 */
@Service
@RequiredArgsConstructor
public class ReportInterpretationService {

    private static final long TEN_MB = 10L * 1024 * 1024;
    private static final long TWENTY_MB = 20L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private final ReportInterpretationPersistence persistence;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final ReportUploadStagingService staging;

    public ReportView finalizeStaged(Long patientId, Long conversationId, String requestId) {
        ReportInterpretation existing = persistence.findByRequest(patientId, requestId);
        if (existing != null) {
            staging.discard(patientId, requestId);
            return toView(existing);
        }
        return interpret(patientId, conversationId, requestId, staging.take(patientId, requestId));
    }

    public ReportView interpret(Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        ReportInterpretation existing = persistence.findByRequest(patientId, requestId);
        if (existing != null) {
            return toView(existing);
        }
        validate(files, requestId);
        ReportInterpretation processing;
        try {
            processing = persistence.start(patientId, conversationId, requestId, files);
        } catch (DataIntegrityViolationException duplicateRequest) {
            // 唯一键收敛并发重复提交；后到请求复用先到请求的业务记录。
            ReportInterpretation raced = persistence.findByRequest(patientId, requestId);
            if (raced != null) {
                return toView(raced);
            }
            throw duplicateRequest;
        }
        try {
            // 网络调用故意不在 @Transactional 方法内，避免长事务占用连接与锁。
            AgentClient.VisionResponse response = agentClient.interpretVision(files);
            String resultJson = objectMapper.writeValueAsString(response.result());
            String contextSummary = contextSummary(response.result());
            return toView(persistence.succeed(processing, response, resultJson, contextSummary));
        } catch (AgentClient.VisionAgentException e) {
            persistence.fail(processing, e.code());
            throw new ApiException(e.status(), e.code(), e.getMessage());
        } catch (Exception e) {
            // 失败只记录稳定错误码，绝不持久化模型原始输出或报告原文。
            persistence.fail(processing, "VISION_PROCESSING_FAILED");
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(502, "本次未能可靠解读，请重新上传更清晰的报告");
        }
    }

    private void validate(List<MultipartFile> files, String requestId) {
        if (requestId.length() > 64 || files.isEmpty() || files.size() > 5) {
            throw new ApiException(422, "报告上传参数无效");
        }
        boolean pdf = files.size() == 1 && "application/pdf".equals(files.get(0).getContentType());
        long total = 0;
        for (MultipartFile file : files) {
            total += file.getSize();
            if (file.isEmpty() || file.getSize() > TEN_MB || (!pdf && !IMAGE_TYPES.contains(file.getContentType()))) {
                throw new ApiException(422, "仅支持规定大小的 JPEG、PNG 或 PDF 报告");
            }
        }
        if (!pdf && total > TWENTY_MB) {
            throw new ApiException(422, "报告图片总量不能超过 20MB");
        }
    }

    private String contextSummary(JsonNode result) {
        StringBuilder summary =
                new StringBuilder("报告解读：").append(result.path("summary").asText());
        for (JsonNode item : result.path("items")) {
            summary.append("；")
                    .append(item.path("name").asText())
                    .append(" ")
                    .append(item.path("value").asText())
                    .append("（参考 ")
                    .append(item.path("reference_range").asText())
                    .append("）")
                    .append("，关注级别 ")
                    .append(item.path("priority").asText());
            if (summary.length() >= 1900) {
                break;
            }
        }
        return summary.substring(0, Math.min(summary.length(), 2000));
    }

    private ReportView toView(ReportInterpretation record) {
        try {
            JsonNode result = record.getResultJson() == null ? null : objectMapper.readTree(record.getResultJson());
            return new ReportView(
                    record.getId(),
                    record.getConversationId(),
                    record.getStatus(),
                    record.getPageCount(),
                    result,
                    record.getDisclaimer());
        } catch (Exception e) {
            throw new ApiException(500, "报告解读记录损坏");
        }
    }

    public record ReportView(
            @JsonProperty("report_interpretation_id") Long reportInterpretationId,
            @JsonProperty("conversation_id") Long conversationId,
            String status,
            @JsonProperty("page_count") Integer pageCount,
            JsonNode result,
            String disclaimer) {}
}
