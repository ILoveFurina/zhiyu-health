package com.zhiyu.health.controller.c;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.MedicationLookupService;
import com.zhiyu.health.service.PillBoxPhotoService;
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
 * C 端拍药盒与文字查药入口（票 14，ADR-0025 差异化点 4）。
 *
 * <p>两个入口共用同一 {@link MedicationLookupService} 查询与规则出口，只是输入来源不同：
 * <ul>
 *   <li>{@code POST /api/c/pill-box-photos}：拍照版，server-py vision 提候选药名
 *   <li>{@code POST /api/c/medication-lookups}：文字版，直接收药名
 * </ul>
 * 纯薄壳，只校验与装配，业务在 service。
 */
@Validated
@RestController
@RequiredArgsConstructor
public class MedicationLookupController {

    private final PillBoxPhotoService pillBoxPhotoService;
    private final MedicationLookupService medicationLookupService;

    /** 拍药盒：上传药盒照片 -> vision OCR 提名 -> 双出口卡片回落。 */
    @PostMapping("/api/c/pill-box-photos")
    public PillBoxPhotoService.PillBoxPhotoView analyzePillBox(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("request_id") @NotBlank String requestId,
            @RequestParam(value = "conversation_id", required = false) Long conversationId,
            @RequestParam("files") List<MultipartFile> files) {
        return pillBoxPhotoService.analyze(patientId, conversationId, requestId, files);
    }

    /** 查药品（文字版）：直接输入药名 -> 双出口卡片回落，与拍照版共用同一规则出口。 */
    @PostMapping("/api/c/medication-lookups")
    public MedicationLookupService.MedicationLookupView lookupByName(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("request_id") @NotBlank String requestId,
            @RequestParam("medication_name") @NotBlank String medicationName,
            @RequestParam(value = "conversation_id", required = false) Long conversationId) {
        return medicationLookupService.lookupAndAppend(patientId, conversationId, "查药品", List.of(medicationName));
    }
}
