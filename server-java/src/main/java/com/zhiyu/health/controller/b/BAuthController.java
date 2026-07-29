package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B 端认证：登录与当前员工资料，只做参数校验与装配 */
@RestController
@RequestMapping("/api/b/auth")
@RequiredArgsConstructor
public class BAuthController {

    private final AuthService authService;

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 128) String password) {}

    public record TokenResponse(String accessToken, String tokenType) {}

    /** 员工资料：不含 passwordHash */
    public record StaffProfile(String username, String role, Long doctorId) {}

    @PostMapping("/login")
    public TokenResponse login(@Validated @RequestBody LoginRequest request) {
        StaffUser staff = authService.authenticate(request.username(), request.password());
        if (staff == null) {
            throw new ApiException(401, "账号或密码错误");
        }
        return new TokenResponse(authService.createAccessToken(staff), "bearer");
    }

    @GetMapping("/me")
    public StaffProfile me(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        StaffUser staff = authService.profile(staffId);
        if (staff == null) {
            throw new ApiException(401, "登录已失效");
        }
        return new StaffProfile(staff.getUsername(), staff.getRole(), staff.getDoctorId());
    }
}
