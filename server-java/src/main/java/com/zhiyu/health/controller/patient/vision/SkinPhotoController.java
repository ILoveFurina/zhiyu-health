package com.zhiyu.health.controller.patient.vision;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.vision.SkinPhotoService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** C 端拍皮肤分析入口：只校验与装配，业务在 service。 */
@Validated
@RestController
@RequiredArgsConstructor
public class SkinPhotoController {

    private final SkinPhotoService service;

    @PostMapping("/api/c/skin-photos")
    public SkinPhotoService.SkinAnalysisView analyze(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("request_id") @NotBlank String requestId,
            @RequestParam(value = "conversation_id", required = false) Long conversationId,
            @RequestParam("files") List<MultipartFile> files) {
        return service.analyze(patientId, conversationId, requestId, files);
    }
}
