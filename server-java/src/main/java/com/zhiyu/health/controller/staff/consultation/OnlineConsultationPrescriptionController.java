package com.zhiyu.health.controller.staff.consultation;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.staff.prescription.DoctorPrescriptionController;
import com.zhiyu.health.controller.staff.prescription.mapping.PrescriptionInputMapper;
import com.zhiyu.health.service.prescription.PrescriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线问诊开方（票 56）：请求/响应形状与线下开方端点一致，
 * 患者/健康档案/医生身份由服务端统一临床上下文派生，绝不接受请求体传入。
 */
@RestController
@RequestMapping("/api/b/reception/online-consultations")
@RequiredArgsConstructor
public class OnlineConsultationPrescriptionController {
    private final PrescriptionService service;
    private final PrescriptionInputMapper inputMapper;

    @PostMapping("/{id}/prescriptions")
    public PrescriptionService.PrescriptionView create(
            @PathVariable long id,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId,
            @Valid @RequestBody DoctorPrescriptionController.CreateInput input) {
        return service.createFromOnlineConsultation(inputMapper.toOnlineCommand(staffId, id, input));
    }

    /** 开方过程中的实时禁忌/相互作用检查；判定与提交侧强制复跑的是同一确定性规则。 */
    @PostMapping("/{id}/contraindication-check")
    public DoctorPrescriptionController.SafetyCheckResponse checkSafety(
            @PathVariable long id,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId,
            @Valid @RequestBody DoctorPrescriptionController.SafetyCheckInput input) {
        return inputMapper.toSafetyResponse(
                service.checkSafetyFromOnlineConsultation(inputMapper.toOnlineSafetyCommand(staffId, id, input)));
    }
}
