package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import com.zhiyu.health.mapper.ReportInterpretationMapper;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 报告解读短事务边界；模型网络调用不得进入本类事务。 */
@Service
@RequiredArgsConstructor
public class ReportInterpretationPersistence {

    private final ReportInterpretationMapper mapper;
    private final ConversationService conversations;
    private final ObjectMapper objectMapper;
    private final DisclaimerService disclaimers;
    private final HealthProfileService healthProfiles;
    private final HealthObservationMapping observationMapping;
    private final HealthObservationMapper observationMapper;
    private final Contracts contracts;

    public ReportInterpretation findByRequest(Long patientId, String requestId) {
        return mapper.selectOne(new LambdaQueryWrapper<ReportInterpretation>()
                .eq(ReportInterpretation::getPatientId, patientId)
                .eq(ReportInterpretation::getRequestId, requestId));
    }

    public List<ReportInterpretation> listForPatient(long patientId) {
        return mapper.selectHistoryByPatient(patientId);
    }

    public ReportInterpretation findOwned(long patientId, long id) {
        return mapper.selectOwned(id, patientId);
    }

    @Transactional
    public ReportInterpretation start(
            Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, "看报告");
        ReportInterpretation record = new ReportInterpretation();
        record.setPatientId(patientId);
        record.setHealthProfileId(healthProfiles.requireActive(patientId).getId());
        record.setConversationId(conversation.getId());
        record.setRequestId(requestId);
        record.setFileType(isPdf(files) ? "PDF" : "IMAGE");
        record.setFileName(isPdf(files) ? "报告.pdf" : "报告图片（" + files.size() + "张）");
        record.setStatus("PROCESSING");
        record.setDisclaimer(disclaimers.text());
        mapper.insert(record);

        ObjectNode upload = objectMapper
                .createObjectNode()
                .put("report_interpretation_id", record.getId())
                .put("file_name", record.getFileName())
                .put("file_type", record.getFileType())
                .put("file_count", files.size());
        conversations.appendMessage(
                conversation.getId(), "user", upload.toString(), Message.KIND_REPORT_UPLOAD, null, record.getId());
        return record;
    }

    @Transactional
    public ReportInterpretation succeed(
            ReportInterpretation record,
            AgentClient.VisionResponse response,
            String resultJson,
            String contextSummary) {
        record.setStatus("SUCCEEDED");
        record.setPageCount(response.pageCount());
        record.setResultJson(resultJson);
        record.setContextSummary(contextSummary);
        record.setErrorCode(null);
        record.setUpdatedAt(OffsetDateTime.now());
        // server-java 在出口固定兜底，避免 Agent 层漏传或篡改法定提示语。
        record.setDisclaimer(disclaimers.text());
        mapper.updateById(record);
        // 健康观测沉淀与报告成功落库同事务（票 61，ADR-0031）：任一步失败整体回滚，
        // 报告保持 PROCESSING 可由 request_id 重试，绝不留下"报告成功但观测半提交"的中间态。
        depositObservations(record, resultJson);

        ObjectNode card = objectMapper
                .createObjectNode()
                .put("report_interpretation_id", record.getId())
                .put("page_count", response.pageCount())
                .put("disclaimer", disclaimers.text())
                .set("result", response.result());
        conversations.appendMessage(
                record.getConversationId(),
                "assistant",
                card.toString(),
                Message.KIND_REPORT_INTERPRETATION,
                null,
                record.getId());
        conversations.appendMessage(
                record.getConversationId(),
                "assistant",
                contextSummary,
                Message.KIND_REPORT_CONTEXT,
                null,
                record.getId());
        return record;
    }

    @Transactional
    public void fail(ReportInterpretation record, String errorCode) {
        record.setStatus("FAILED");
        record.setErrorCode(errorCode);
        record.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(record);
    }

    /**
     * 观测沉淀：确定性映射候选逐条 INSERT。单项不可沉淀由映射组件确定性跳过（不产生候选），
     * 不靠 try-catch 吞异常；跨报告同日槽位冲突交给 ON CONFLICT DO NOTHING（禁止先查后改），
     * 影响行数 0 即 DUPLICATE_SLOT，详情读取时推导，无需落库。同报告幂等由
     * uq_health_observations_report_metric + interpret() 的 request_id 短路保证。
     */
    private void depositObservations(ReportInterpretation record, String resultJson) {
        JsonNode result;
        try {
            result = objectMapper.readTree(resultJson);
        } catch (Exception e) {
            // resultJson 由本服务刚从模型响应序列化得到，解析失败属数据损坏而非业务跳过
            throw new IllegalStateException("报告解读结果 JSON 损坏", e);
        }
        HealthObservationMapping.ReportMapping mapping = observationMapping.mapReport(result);
        if (mapping.observedOn() == null) {
            return;
        }
        Contracts.HealthObservations contract = contracts.healthObservations();
        for (HealthObservationMapping.Candidate candidate : mapping.candidates()) {
            HealthObservation observation = new HealthObservation();
            observation.setHealthProfileId(record.getHealthProfileId());
            observation.setReportInterpretationId(record.getId());
            observation.setMetricCode(candidate.metricCode());
            observation.setValueNumeric(candidate.valueNumeric());
            observation.setValueCategory(candidate.valueCategory());
            observation.setUnit(candidate.unit());
            observation.setReferenceRange(candidate.referenceRange());
            observation.setObservedOn(mapping.observedOn());
            observation.setSourceType(contract.reportAiSource());
            observation.setVerificationStatus(contract.unverifiedStatus());
            observation.setCurrent(true);
            observationMapper.insertIgnoreSlot(observation);
        }
    }

    private boolean isPdf(List<MultipartFile> files) {
        return files.size() == 1 && "application/pdf".equals(files.get(0).getContentType());
    }
}
