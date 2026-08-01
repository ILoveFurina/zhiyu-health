package com.zhiyu.health.controller.c;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.ReportInterpretationService;
import com.zhiyu.health.service.ReportUploadStagingService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** C 端报告解读上传入口：只校验与装配，业务在 service。 */
@Validated
@RestController
@RequiredArgsConstructor
public class ReportInterpretationController {

    private final ReportInterpretationService service;
    private final ReportUploadStagingService staging;

    @GetMapping("/api/c/report-interpretations")
    public List<ReportInterpretationService.ReportView> list(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return service.listForPatient(patientId);
    }

    @PostMapping("/api/c/report-interpretations")
    public ReportInterpretationService.ReportView interpret(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("request_id") @NotBlank String requestId,
            @RequestParam(value = "conversation_id", required = false) Long conversationId,
            @RequestParam("files") List<MultipartFile> files) {
        return service.interpret(patientId, conversationId, requestId, files);
    }

    @PostMapping("/api/c/report-interpretation-uploads")
    public ReportUploadStagingService.UploadProgress upload(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @RequestParam("request_id") @NotBlank String requestId,
            @RequestParam("page_index") int pageIndex,
            @RequestParam("total_files") int totalFiles,
            @RequestParam("media_type") String mediaType,
            @RequestParam("file") MultipartFile file) {
        return staging.add(patientId, requestId, pageIndex, totalFiles, file, mediaType);
    }

    @PostMapping("/api/c/report-interpretations/finalize")
    public ReportInterpretationService.ReportView finalizeUpload(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @RequestBody FinalizeRequest request) {
        return service.finalizeStaged(patientId, request.conversationId(), request.requestId());
    }

    public record FinalizeRequest(
            @JsonProperty("request_id") @NotBlank String requestId,
            @JsonProperty("conversation_id") Long conversationId) {}
}
