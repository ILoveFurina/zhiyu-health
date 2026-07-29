package com.zhiyu.health.controller.c;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.service.ReportInterpretationService;
import com.zhiyu.health.service.ReportUploadStagingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 报告解读上传的 C 端 HTTP seam。 */
class ReportInterpretationControllerTest {

    @Test
    void patientUploadsReportThroughJavaAndReceivesStructuredCard() throws Exception {
        ReportInterpretationService service = mock(ReportInterpretationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(service.interpret(eq(12L), isNull(), eq("req-001"), anyList())).thenReturn(
                new ReportInterpretationService.ReportView(
                        31L,
                        7L,
                        "SUCCEEDED",
                        1,
                        objectMapper.readTree("""
                                {"summary":"血红蛋白偏低","items":[{"name":"血红蛋白"}],
                                 "actions":["咨询医生"],"unreadable":[]}
                                """),
                        "仅供参考，不替代医生诊断"));
        MockMvc mvc = standaloneSetup(new ReportInterpretationController(
                service, mock(ReportUploadStagingService.class))).build();
        MockMultipartFile image = new MockMultipartFile(
                "files", "report.png", "image/png", new byte[]{1, 2, 3});

        mvc.perform(multipart("/api/c/report-interpretations")
                        .file(image)
                        .param("request_id", "req-001")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report_interpretation_id").value(31))
                .andExpect(jsonPath("$.conversation_id").value(7))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.page_count").value(1))
                .andExpect(jsonPath("$.result.summary").value("血红蛋白偏低"))
                .andExpect(jsonPath("$.disclaimer").value("仅供参考，不替代医生诊断"));
    }

    @Test
    void miniProgramFinalizesPreviouslyStagedFilesAsOneReport() throws Exception {
        ReportInterpretationService service = mock(ReportInterpretationService.class);
        ReportUploadStagingService staging = mock(ReportUploadStagingService.class);
        MultipartFile file = mock(MultipartFile.class);
        when(service.finalizeStaged(12L, 9L, "req-staged")).thenReturn(
                new ReportInterpretationService.ReportView(
                        41L, 9L, "SUCCEEDED", 1,
                        new ObjectMapper().readTree("{\"summary\":\"解读完成\"}"),
                        "仅供参考，不替代医生诊断"));
        MockMvc mvc = standaloneSetup(new ReportInterpretationController(service, staging)).build();

        mvc.perform(post("/api/c/report-interpretations/finalize")
                        .contentType("application/json")
                        .content("{\"request_id\":\"req-staged\",\"conversation_id\":9}")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report_interpretation_id").value(41))
                .andExpect(jsonPath("$.result.summary").value("解读完成"));
    }
}
