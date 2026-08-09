package com.zhiyu.health.controller.patient.chat;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.chat.PreconsultationService;
import com.zhiyu.health.service.consultation.PatientConsultationProgressService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端预问诊草稿接口（票 55）：只做身份装配，草稿生命周期归 PreconsultationService。 */
@RestController
@RequestMapping("/api/c/preconsultation-drafts")
@RequiredArgsConstructor
public class PreconsultationController {

    private final PreconsultationService preconsultationService;
    private final PatientConsultationProgressService progressService;

    public record DraftResponse(PreconsultationService.DraftView draft) {}

    public record ProgressResponse(List<PatientConsultationProgressService.ProgressItem> items) {}

    /** 进入在线问诊入口：无激活档案返回 409 引导建档案；有活跃草稿则恢复。 */
    @PostMapping
    public DraftResponse start(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new DraftResponse(preconsultationService.startOrResume(patientId));
    }

    @GetMapping("/{id}")
    public DraftResponse get(@PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new DraftResponse(preconsultationService.get(patientId, id));
    }

    @GetMapping("/progress")
    public ProgressResponse progress(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new ProgressResponse(progressService.list(patientId));
    }

    @PostMapping("/{id}/abandon")
    public DraftResponse abandon(
            @PathVariable long id, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return new DraftResponse(preconsultationService.abandon(patientId, id));
    }
}
