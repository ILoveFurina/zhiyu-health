package com.zhiyu.health.controller.c;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.TonguePhotoService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** C 端拍舌苔中医辨证入口：只校验与装配，业务在 service。 */
@Validated
@RestController
@RequiredArgsConstructor
public class TonguePhotoController {

    private final TonguePhotoService service;

    @PostMapping("/api/c/tongue-photos")
    public TonguePhotoService.TongueAnalysisView analyze(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("request_id") @NotBlank String requestId,
            @RequestParam(value = "conversation_id", required = false) Long conversationId,
            @RequestParam("files") List<MultipartFile> files) {
        return service.analyze(patientId, conversationId, requestId, files);
    }
}
