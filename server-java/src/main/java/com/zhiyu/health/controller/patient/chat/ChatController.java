package com.zhiyu.health.controller.patient.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.chat.ChatRoundModels;
import com.zhiyu.health.service.chat.ChatService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** C 端对话接口：只做参数校验与装配，业务在 ChatService */
@RestController
@RequestMapping("/api/c")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    public record ChatRequest(
            @JsonProperty("request_id") @NotBlank String requestId,
            // content 与 medication_name 互斥（票 51）：对话轮次携带 content，
            // 药品说明书流携带 medication_name；手工校验以给出明确 400 文案
            String content,
            @JsonProperty("medication_name") String medicationName,
            @JsonProperty("conversation_id") Long conversationId,
            String effort,
            String scenario,
            // 知识增强源（ADR-0010）：rag/graph/none；缺省时 server-py 按 scenario 默认
            @JsonProperty("knowledge_source") String knowledgeSource,
            // 用户授权定位后回传的经纬度；拒绝授权时不传，由 Agent 降级提示手动选区
            @JsonProperty("longitude") @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @JsonProperty("latitude") @DecimalMin("-90") @DecimalMax("90") Double latitude,
            // 票 50：科室号源查询失败后重试，复用已确定的标准科室 ID 直查
            @JsonProperty("retry_standard_department_id") Long retryStandardDepartmentId,
            // 票 55：预问诊草稿标识；携带时 server-java 校验归属/状态后强制 preconsultation 场景
            @JsonProperty("preconsultation_draft_id") Long preconsultationDraftId,
            // 票 80：处方药多处方选择卡点选回传的所选处方 ID；server-java 仅透传给 server-py，
            // 归属校验延后到 prepare_drug_order（MedicationToolService.prepareForPrescription），
            // 不改场景/权限，与 content 并存（点选文案作为 content）
            @JsonProperty("prescription_id") Long prescriptionId) {}

    @PostMapping("/chat")
    public SseEmitter chat(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @Validated @RequestBody ChatRequest request) {
        boolean hasMedication =
                request.medicationName() != null && !request.medicationName().isBlank();
        boolean hasContent = request.content() != null && !request.content().isBlank();
        if (hasMedication == hasContent) {
            throw new ApiException(400, "content 与 medication_name 必须且只能携带其一");
        }
        if (hasMedication) {
            return chatService.medication(new ChatRoundModels.MedicationCommand(
                    patientId, request.requestId(), request.conversationId(), request.medicationName()));
        }
        return chatService.chat(new ChatRoundModels.Command(
                patientId,
                request.requestId(),
                request.conversationId(),
                request.content(),
                request.effort(),
                request.scenario(),
                request.knowledgeSource(),
                request.longitude(),
                request.latitude(),
                request.retryStandardDepartmentId(),
                request.preconsultationDraftId(),
                request.prescriptionId()));
    }
}
