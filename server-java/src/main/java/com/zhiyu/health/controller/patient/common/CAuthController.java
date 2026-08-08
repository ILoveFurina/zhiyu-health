package com.zhiyu.health.controller.patient.common;

import com.zhiyu.health.entity.common.Patient;
import com.zhiyu.health.service.common.PatientService;
import com.zhiyu.health.service.common.PatientTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端 mock 登录接口；登录端点由 AuthFilter 放行。 */
@RestController
@RequestMapping("/api/c/auth")
@RequiredArgsConstructor
public class CAuthController {

    private final PatientService patients;
    private final PatientTokenService tokens;

    public record MockLoginRequest(@Size(min = 1, max = 50) String nickname) {}

    public record PatientInfo(Long id, String nickname) {}

    public record MockLoginResponse(String token, PatientInfo patient) {}

    @PostMapping("/mock-login")
    public MockLoginResponse mockLogin(@Valid @RequestBody MockLoginRequest request) {
        Patient patient = patients.mockLogin(request.nickname());
        return new MockLoginResponse(
                tokens.issue(patient.getId()), new PatientInfo(patient.getId(), patient.getNickname()));
    }
}
