package com.zhiyu.health.controller.patient.vision;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.vision.PillBoxPhotoService;
import com.zhiyu.health.service.vision.PillBoxPhotoService.PillBoxPhotoView;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * C 端拍药盒入口（票 51，ADR-0028）。
 *
 * <p>票 14 的「查药品」文字版入口（POST /api/c/medication-lookups）已随双出口卡片删除：
 * 文字版能力改经 chat 信封 medication_name 走通用药品说明书流。本控制器只剩拍照版，
 * 返回 OCR 提名视图（药名列表），说明书由实时通道流式输出。纯薄壳，只校验与装配。
 */
@Validated
@RestController
@RequiredArgsConstructor
public class PillBoxPhotoController {

    private final PillBoxPhotoService pillBoxPhotoService;

    /** 拍药盒：上传药盒照片 -> vision OCR 提名 -> 返回药名列表（说明书走实时通道）。 */
    @PostMapping("/api/c/pill-box-photos")
    public PillBoxPhotoView analyzePillBox(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("request_id") @NotBlank String requestId,
            @RequestParam(value = "conversation_id", required = false) Long conversationId,
            @RequestParam("files") List<MultipartFile> files) {
        return pillBoxPhotoService.analyze(patientId, conversationId, requestId, files);
    }
}
