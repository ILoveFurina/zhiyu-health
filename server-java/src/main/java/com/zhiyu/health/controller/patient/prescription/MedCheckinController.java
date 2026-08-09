package com.zhiyu.health.controller.patient.prescription;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.prescription.MedCheckinService;
import com.zhiyu.health.service.prescription.MedCheckinView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端服药打卡接口：消息页聚合到点 PENDING 提醒，点击"已服用"推进 CHECKED。
 * server-py 不参与，全部 server-java 直写直读（ADR-0017）。
 */
@RestController
@RequestMapping("/api/c/med-checkins")
@RequiredArgsConstructor
public class MedCheckinController {
    private final MedCheckinService service;

    @GetMapping
    public List<MedCheckinView> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return service.pendingReminders(patientId);
    }

    @PostMapping("/{id}/check")
    public MedCheckinView check(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long id) {
        return service.check(patientId, id);
    }
}
