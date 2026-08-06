package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.service.PillBoxPhotoService;
import com.zhiyu.health.service.PillBoxPhotoService.PillBoxPhotoView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 拍药盒 C 端 HTTP seam（票 51，ADR-0028）：响应为 OCR 提名视图，不再回双出口卡片。 */
class PillBoxPhotoControllerTest {

    @Test
    void patientUploadsPillBoxPhotoAndReceivesDrugNames() throws Exception {
        // 票 51：响应 {request_id, conversation_id, recognized, drug_names[], hint?}，
        // 说明书由客户端经 chat 信封 medication_name 走实时通道流式获取
        PillBoxPhotoService pillBoxService = mock(PillBoxPhotoService.class);
        when(pillBoxService.analyze(eq(12L), isNull(), eq("pill-001"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new PillBoxPhotoView("pill-001", 7L, true, List.of("阿莫西林胶囊", "阿莫西林"), null));
        MockMvc mvc = standaloneSetup(new PillBoxPhotoController(pillBoxService)).build();
        MockMultipartFile image = new MockMultipartFile("files", "box.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/pill-box-photos")
                        .file(image)
                        .param("request_id", "pill-001")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("pill-001"))
                .andExpect(jsonPath("$.conversation_id").value(7))
                .andExpect(jsonPath("$.recognized").value(true))
                .andExpect(jsonPath("$.drug_names[0]").value("阿莫西林胶囊"))
                // 无卡片字段（票 14 双出口已删除）
                .andExpect(jsonPath("$.medication_info").doesNotExist())
                .andExpect(jsonPath("$.medication_safety").doesNotExist())
                .andExpect(jsonPath("$.hint").doesNotExist());
        verify(pillBoxService).analyze(eq(12L), isNull(), eq("pill-001"), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void unrecognizedPhotoCarriesHint() throws Exception {
        // vision 未识别药名：recognized=false 且携带引导文案
        PillBoxPhotoService pillBoxService = mock(PillBoxPhotoService.class);
        when(pillBoxService.analyze(eq(12L), isNull(), eq("pill-nf"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new PillBoxPhotoView(
                        "pill-nf", 7L, false, List.of(), "未能识别药盒上的药名，请重拍清晰的药盒照片或直接输入药名。"));
        MockMvc mvc = standaloneSetup(new PillBoxPhotoController(pillBoxService)).build();
        MockMultipartFile image = new MockMultipartFile("files", "box.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/c/pill-box-photos")
                        .file(image)
                        .param("request_id", "pill-nf")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recognized").value(false))
                .andExpect(jsonPath("$.drug_names").isEmpty())
                .andExpect(jsonPath("$.hint").value("未能识别药盒上的药名，请重拍清晰的药盒照片或直接输入药名。"));
    }
}
