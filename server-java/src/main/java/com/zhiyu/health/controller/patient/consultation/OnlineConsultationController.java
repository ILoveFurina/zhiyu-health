package com.zhiyu.health.controller.patient.consultation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.consultation.OnlineConsultationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** C 端在线问诊接口（票 55）：只做校验与装配，状态机与归属校验归 OnlineConsultationService。
 *  显式 bean 名：与 controller/b 同名类区分，避免默认 bean 名冲突导致启动失败。 */
@RestController("cOnlineConsultationController")
@RequestMapping("/api/c/online-consultations")
@RequiredArgsConstructor
public class OnlineConsultationController {

    private final OnlineConsultationService consultations;

    public record ConfirmInput(@JsonProperty("draft_id") @NotNull Long draftId) {}

    public record MessageInput(@NotBlank @Size(max = 2000) String content) {}

    public record ConsultationResponse(OnlineConsultationService.ConsultationDetail consultation) {}

    public record ConsultationListResponse(List<OnlineConsultationService.ConsultationListItem> consultations) {}

    public record MessageListResponse(List<OnlineConsultationService.MessageView> messages) {}

    public record MessageResponse(OnlineConsultationService.MessageView message) {}

    /** 确认摘要并建单（幂等）：重复确认返回同一问诊单。 */
    @PostMapping
    public ConsultationResponse create(
            @Valid @RequestBody ConfirmInput input, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new ConsultationResponse(consultations.confirm(patientId, input.draftId()));
    }

    @GetMapping
    public ConsultationListResponse list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new ConsultationListResponse(consultations.listMine(patientId));
    }

    @GetMapping("/{id}")
    public ConsultationResponse detail(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new ConsultationResponse(consultations.detail(patientId, id));
    }

    @PostMapping("/{id}/cancel")
    public ConsultationResponse cancel(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new ConsultationResponse(consultations.cancel(patientId, id));
    }

    @PostMapping("/{id}/resubmit")
    public ConsultationResponse resubmit(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new ConsultationResponse(consultations.resubmit(patientId, id));
    }

    @GetMapping("/{id}/messages")
    public MessageListResponse messages(
            @PathVariable long id,
            @RequestParam(value = "after_id", defaultValue = "0") long afterId,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new MessageListResponse(consultations.listMessagesForPatient(patientId, id, afterId));
    }

    @PostMapping("/{id}/messages")
    public MessageResponse send(
            @PathVariable long id,
            @Valid @RequestBody MessageInput input,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new MessageResponse(consultations.sendMessageForPatient(
                patientId, id, input.content().trim()));
    }

    /** 患者发送问诊图片（票 58，ADR-0029）：纯薄壳，图片校验与状态/归属守卫全在 service。
     * 与拍药盒链路（PillBoxPhotoController）一致：不在 controller 重复校验图片格式/大小，
     * 因 service 用 PhotoFileTypes 做 magic bytes 回退（支付宝 my.uploadFile 不可靠设置 Content-Type）。 */
    @PostMapping("/{id}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse sendPhoto(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new MessageResponse(consultations.sendImageForPatient(patientId, id, file));
    }
}
