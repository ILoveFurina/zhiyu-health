package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** 拍皮肤分析编排：图片旁路持久化、卡片回落与失败兜底话术。 */
class SkinPhotoServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyzePersistsPhotosThenCardAndReturnsView() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍皮肤"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        JsonNode result = objectMapper.readTree(
                """
                {"skin_type":"偏干性","findings":[],"care_summary":"注意保湿","need_doctor":false}
                """);
        when(agentClient.interpretVision(anyList(), any(), eq("SKIN")))
                .thenReturn(new AgentClient.VisionResponse(result, "仅供参考，不替代医生诊断", null, 1));
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        SkinPhotoService service = new SkinPhotoService(
                conversations,
                agentClient,
                objectMapper,
                TestContracts.instance(),
                healthProfiles,
                TestDisclaimers.instance(),
                minioStorage);

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        SkinPhotoService.SkinAnalysisView view = service.analyze(12L, null, "skin-001", List.of(file));

        assertThat(view.conversationId()).isEqualTo(7L);
        assertThat(view.result().path("skin_type").asText()).isEqualTo("偏干性");
        assertThat(view.disclaimer()).isEqualTo("仅供参考，不替代医生诊断");
        // 图片旁路持久化先行，分析卡片随后；顺序钉死以保证"先留图后分析"
        InOrder order = inOrder(minioStorage, conversations, agentClient);
        order.verify(minioStorage).persistPhotosAndMessages(eq(7L), anyList());
        order.verify(agentClient).interpretVision(anyList(), any(), eq("SKIN"));
        // skin_analysis 卡片以 assistant 角色回落
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq("skin_analysis"), any(), any(), any());
    }

    @Test
    void agentFailureAppendsFallbackCardWithDoctorAdvice() {
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍皮肤"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        when(agentClient.interpretVision(anyList(), any(), eq("SKIN")))
                .thenThrow(new AgentClient.VisionAgentException("VISION_MODEL_TIMEOUT", 504, "超时"));
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        SkinPhotoService service = new SkinPhotoService(
                conversations,
                agentClient,
                objectMapper,
                TestContracts.instance(),
                healthProfiles,
                TestDisclaimers.instance(),
                minioStorage);

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        assertThatThrownBy(() -> service.analyze(12L, null, "skin-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(504);
                    assertThat(error.getCode()).isEqualTo("VISION_MODEL_TIMEOUT");
                });
        // 失败时仍回落一条 skin_analysis 兜底卡片，need_doctor=true 引导就医
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq("skin_analysis"), any(), any(), any());
    }

    @Test
    void nonImageFileIsRejectedBeforeAnyCall() {
        SkinPhotoService service = new SkinPhotoService(
                mock(ConversationService.class),
                mock(AgentClient.class),
                objectMapper,
                TestContracts.instance(),
                mock(HealthProfileService.class),
                TestDisclaimers.instance(),
                mock(MinioStorageService.class));
        MultipartFile file = new MockMultipartFile("files", "doc.pdf", "application/pdf", "not an image".getBytes());
        assertThatThrownBy(() -> service.analyze(12L, null, "skin-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(422);
                });
    }
}
