package com.zhiyu.health.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.service.mapping.ReportInterpretationDtoMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 报告解读业务编排；外部模型调用发生在两个短事务之间。 */
@Service
@RequiredArgsConstructor
public class ReportInterpretationService {

    private final ReportInterpretationPersistence persistence;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final ReportUploadStagingService staging;
    // 上传限制唯一事实源是 contracts/upload-limits.json（两端入口校验必须一致）
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final DisclaimerService disclaimers;
    private final ReportInterpretationDtoMapper reportDtos;
    private final HealthObservationMapping observationMapping;
    private final HealthObservationService observations;

    public List<ReportView> listForPatient(long patientId) {
        return persistence.listForPatient(patientId).stream().map(this::toView).toList();
    }

    /**
     * 报告详情（票 61，ADR-0031）：逐项沉淀状态由 HealthObservationMapping 在读取时重算推导，不落库。
     * 已沉淀项叠加观测记录的核验状态；未沉淀项按映射结果给 NO_DATE/CONFLICT_SKIPPED/DUPLICATE_SLOT/UNMAPPED。
     */
    public ReportDetailView detail(long patientId, long reportId) {
        ReportInterpretation record = persistence.findOwned(patientId, reportId);
        if (record == null) {
            throw new ApiException(404, "报告解读不存在");
        }
        JsonNode result = null;
        List<DetailItem> items = List.of();
        if (record.getResultJson() != null) {
            try {
                result = objectMapper.readTree(record.getResultJson());
            } catch (Exception e) {
                throw new ApiException(500, "报告解读记录损坏");
            }
            items = detailItems(record, result);
        }
        return new ReportDetailView(
                record.getId(),
                record.getHealthProfileId(),
                record.getConversationId(),
                record.getFileName(),
                record.getFileType(),
                record.getPageCount(),
                record.getStatus(),
                record.getCreatedAt() == null ? null : record.getCreatedAt().toString(),
                result == null ? null : textOrNull(result, "sample_or_exam_date"),
                result == null ? null : textOrNull(result, "report_date"),
                result,
                items,
                disclaimers.text());
    }

    private List<DetailItem> detailItems(ReportInterpretation record, JsonNode result) {
        Contracts.HealthObservations contract = contracts.healthObservations();
        HealthObservationMapping.ReportMapping mapping = observationMapping.mapReport(result);
        // 该报告全部观测按指标分组：优先 current 行（纠错后新记录），历史版本仅作兜底
        Map<String, List<HealthObservation>> byMetric = new LinkedHashMap<>();
        for (HealthObservation observation : observations.forReport(record.getId())) {
            byMetric.computeIfAbsent(observation.getMetricCode(), code -> new ArrayList<>())
                    .add(observation);
        }
        JsonNode rawItems = result.path("items");
        List<DetailItem> items = new ArrayList<>();
        Map<Integer, HealthObservationMapping.ItemOutcome> outcomes = new LinkedHashMap<>();
        mapping.items().forEach(outcome -> outcomes.put(outcome.index(), outcome));
        for (int index = 0; index < rawItems.size(); index++) {
            JsonNode raw = rawItems.get(index);
            HealthObservationMapping.ItemOutcome outcome = outcomes.get(index);
            String itemState;
            List<Long> observationIds = new ArrayList<>();
            if (outcome == null || outcome.candidates().isEmpty()) {
                itemState = outcome == null ? contract.itemStates().get("unmapped") : outcome.state();
            } else {
                itemState = depositedState(contract, outcome, byMetric, observationIds);
            }
            items.add(new DetailItem(
                    index,
                    textOrNull(raw, "name"),
                    textOrNull(raw, "value"),
                    textOrNull(raw, "unit"),
                    textOrNull(raw, "reference_range"),
                    textOrNull(raw, "priority"),
                    textOrNull(raw, "explanation"),
                    textOrNull(raw, "action"),
                    raw.path("page").isInt() ? raw.path("page").asInt() : null,
                    itemState,
                    observations.itemStateLabel(itemState),
                    List.copyOf(observationIds)));
        }
        return List.copyOf(items);
    }

    /** 已映射项的沉淀状态：无观测行说明被同日槽位吞掉（DUPLICATE_SLOT）；多指标项取最保守状态。 */
    private String depositedState(
            Contracts.HealthObservations contract,
            HealthObservationMapping.ItemOutcome outcome,
            Map<String, List<HealthObservation>> byMetric,
            List<Long> observationIds) {
        String unresolved = null;
        for (HealthObservationMapping.Candidate candidate : outcome.candidates()) {
            HealthObservation observation = effectiveRow(byMetric.get(candidate.metricCode()));
            if (observation == null) {
                unresolved = contract.itemStates().get("duplicate_slot");
                continue;
            }
            observationIds.add(observation.getId());
            String deposited = depositedStateOf(contract, observation.getVerificationStatus());
            // 组合项（血压拆两条）状态合并：待核验最优先暴露，其次已排除，已确认最收敛
            if (unresolved == null
                    || contract.itemStates().get("deposited_unverified").equals(deposited)
                    || (contract.itemStates().get("deposited_rejected").equals(deposited)
                            && !contract.itemStates()
                                    .get("deposited_unverified")
                                    .equals(unresolved))) {
                unresolved = deposited;
            }
        }
        return unresolved;
    }

    private HealthObservation effectiveRow(List<HealthObservation> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.getCurrent()))
                .findFirst()
                .orElse(rows.get(rows.size() - 1));
    }

    private String depositedStateOf(Contracts.HealthObservations contract, String verificationStatus) {
        if (contract.rejectedStatus().equals(verificationStatus)) {
            return contract.itemStates().get("deposited_rejected");
        }
        if (contract.userConfirmedStatus().equals(verificationStatus)
                || contract.supersededStatus().equals(verificationStatus)) {
            // SUPERSEDED 旧行只在没有 current 新行时兜底出现，视为已被确认链路覆盖
            return contract.itemStates().get("deposited_confirmed");
        }
        return contract.itemStates().get("deposited_unverified");
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

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
            HealthProfileService.AgentProfileContext profile =
                    healthProfiles.agentContext(patientId, processing.getHealthProfileId());
            AgentClient.VisionResponse response = agentClient.interpretVision(files, profile, "REPORT");
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
        Contracts.UploadLimits limits = contracts.uploadLimits();
        if (requestId.length() > 64 || files.size() < limits.minFiles() || files.size() > limits.maxFiles()) {
            throw new ApiException(422, "报告上传参数无效");
        }
        boolean pdf = files.size() == 1 && limits.pdfType().equals(files.get(0).getContentType());
        long total = 0;
        for (MultipartFile file : files) {
            total += file.getSize();
            if (file.isEmpty()
                    || file.getSize() > limits.maxFileBytes()
                    || (!pdf && !limits.imageTypes().contains(file.getContentType()))) {
                throw new ApiException(422, "仅支持规定大小的 JPEG、PNG 或 PDF 报告");
            }
        }
        if (!pdf && total > limits.maxTotalBytes()) {
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
            // 历史数据中的 disclaimer 可能为空或被污染，server-java 出口始终挂载唯一固定文案。
            return reportDtos.toView(record, result, disclaimers.text());
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

    /** 报告详情（票 61）：result 原始 JSON + 逐项沉淀状态推导。 */
    public record ReportDetailView(
            Long id,
            @JsonProperty("health_profile_id") Long healthProfileId,
            @JsonProperty("conversation_id") Long conversationId,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("file_type") String fileType,
            @JsonProperty("page_count") Integer pageCount,
            String status,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("sample_or_exam_date") String sampleOrExamDate,
            @JsonProperty("report_date") String reportDate,
            JsonNode result,
            List<DetailItem> items,
            String disclaimer) {}

    public record DetailItem(
            int index,
            String name,
            String value,
            String unit,
            @JsonProperty("reference_range") String referenceRange,
            String priority,
            String explanation,
            String action,
            Integer page,
            @JsonProperty("item_state") String itemState,
            @JsonProperty("item_state_label") String itemStateLabel,
            @JsonProperty("observation_ids") List<Long> observationIds) {}
}
