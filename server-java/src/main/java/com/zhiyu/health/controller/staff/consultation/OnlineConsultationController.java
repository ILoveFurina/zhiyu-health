package com.zhiyu.health.controller.staff.consultation;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.consultation.OnlineConsultationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端医生在线问诊接口（票 55）：挂在接诊台命名空间下（AdminInterceptor 放行），
 * doctor 角色与科室资格由 OnlineConsultationService 在业务层派生与校验。
 * 显式 bean 名：与 controller/c 同名类区分，避免默认 bean 名冲突导致启动失败。
 */
@RestController("bOnlineConsultationController")
@RequestMapping("/api/b/reception/online-consultations")
@RequiredArgsConstructor
public class OnlineConsultationController {

    private final OnlineConsultationService consultations;

    public record StartMethodInput(@NotBlank String method) {}

    public record CompleteInput(
            @NotBlank @Size(max = 2000) String diagnosis, @NotBlank @Size(max = 2000) String advice) {}

    public record MessageInput(@NotBlank @Size(max = 2000) String content) {}

    public record ConsultationListResponse(List<OnlineConsultationService.DoctorListItem> consultations) {}

    public record ConsultationResponse(OnlineConsultationService.DoctorConsultationDetail consultation) {}

    public record MessageListResponse(List<OnlineConsultationService.MessageView> messages) {}

    public record MessageResponse(OnlineConsultationService.MessageView message) {}

    public record ConsultationPrescriptionResponse(
            OnlineConsultationService.ConsultationPrescriptionView prescription) {}

    /** 科室待接诊池：只看得到映射同标准科室的待接诊单。 */
    @GetMapping("/pool")
    public ConsultationListResponse pool(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new ConsultationListResponse(consultations.pool(staffId));
    }

    @GetMapping("/mine")
    public ConsultationListResponse mine(
            @RequestParam(required = false) String status,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new ConsultationListResponse(consultations.mine(staffId, status));
    }

    /** 查看摘要不推进状态（Spec 0003：只有明确接受才绑定医生）。 */
    @GetMapping("/{id}")
    public ConsultationResponse detail(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new ConsultationResponse(consultations.detailForDoctor(staffId, id));
    }

    @PostMapping("/{id}/accept")
    public ConsultationResponse accept(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new ConsultationResponse(consultations.accept(staffId, id));
    }

    @PostMapping("/{id}/start-method")
    public ConsultationResponse startMethod(
            @PathVariable long id,
            @Valid @RequestBody StartMethodInput input,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new ConsultationResponse(consultations.startMethod(staffId, id, input.method()));
    }

    @GetMapping("/{id}/messages")
    public MessageListResponse messages(
            @PathVariable long id,
            @RequestParam(value = "after_id", defaultValue = "0") long afterId,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new MessageListResponse(consultations.listMessagesForDoctor(staffId, id, afterId));
    }

    @PostMapping("/{id}/messages")
    public MessageResponse send(
            @PathVariable long id,
            @Valid @RequestBody MessageInput input,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new MessageResponse(
                consultations.sendMessageForDoctor(staffId, id, input.content().trim()));
    }

    @PostMapping("/{id}/complete")
    public ConsultationResponse complete(
            @PathVariable long id,
            @Valid @RequestBody CompleteInput input,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new ConsultationResponse(consultations.complete(staffId, id, input.diagnosis(), input.advice()));
    }

    /** 接诊抽屉按问诊单查处方（票 60）：归属校验在业务层，无处方时 prescription 为 null。 */
    @GetMapping("/{id}/prescription")
    public ConsultationPrescriptionResponse prescription(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        return new ConsultationPrescriptionResponse(consultations.prescriptionForConsultation(staffId, id));
    }
}
