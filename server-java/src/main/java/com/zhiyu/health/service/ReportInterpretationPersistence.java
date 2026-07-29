package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.entity.ReportInterpretation;
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

    public ReportInterpretation findByRequest(Long patientId, String requestId) {
        return mapper.selectOne(new LambdaQueryWrapper<ReportInterpretation>()
                .eq(ReportInterpretation::getPatientId, patientId)
                .eq(ReportInterpretation::getRequestId, requestId));
    }

    @Transactional
    public ReportInterpretation start(
            Long patientId, Long conversationId, String requestId, List<MultipartFile> files) {
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, "看报告");
        ReportInterpretation record = new ReportInterpretation();
        record.setPatientId(patientId);
        record.setConversationId(conversation.getId());
        record.setRequestId(requestId);
        record.setFileType(isPdf(files) ? "PDF" : "IMAGE");
        record.setFileName(isPdf(files) ? "报告.pdf" : "报告图片（" + files.size() + "张）");
        record.setStatus("PROCESSING");
        record.setDisclaimer(ChatService.DISCLAIMER);
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
        record.setDisclaimer(ChatService.DISCLAIMER);
        mapper.updateById(record);

        ObjectNode card = objectMapper
                .createObjectNode()
                .put("report_interpretation_id", record.getId())
                .put("page_count", response.pageCount())
                .put("disclaimer", ChatService.DISCLAIMER)
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

    private boolean isPdf(List<MultipartFile> files) {
        return files.size() == 1 && "application/pdf".equals(files.get(0).getContentType());
    }
}
