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
import org.springframework.web.multipart.MultipartFile;

/** 拍饮食分析编排：图片旁路持久化、卡片回落与失败兜底话术。 */
class DietPhotoServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyzePersistsPhotosThenCardAndReturnsView() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍饮食"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        JsonNode result = objectMapper.readTree(
                """
                {"meal_type":"午餐","foods":[],"estimated_calories":"约450千卡",\
                "nutrition_summary":"碳水为主","diet_advice":"增加蔬菜","personal_tip":"","need_doctor":false}
                """);
        when(agentClient.interpretVision(anyList(), any(), eq("DIET")))
                .thenReturn(new AgentClient.VisionResponse(result, "仅供参考，不替代医生诊断", 1));
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        DietPhotoService service = new DietPhotoService(
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
        DietPhotoService.DietAnalysisView view = service.analyze(12L, null, "diet-001", List.of(file));

        assertThat(view.conversationId()).isEqualTo(7L);
        assertThat(view.result().path("meal_type").asText()).isEqualTo("午餐");
        assertThat(view.disclaimer()).isEqualTo("仅供参考，不替代医生诊断");
        // 图片旁路持久化先行，分析卡片随后；顺序钉死以保证"先留图后分析"
        InOrder order = inOrder(minioStorage, conversations, agentClient);
        order.verify(minioStorage).persistPhotosAndMessages(eq(7L), anyList());
        order.verify(agentClient).interpretVision(anyList(), any(), eq("DIET"));
        // diet_analysis 卡片以 assistant 角色回落
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq("diet_analysis"), any(), any(), any());
    }

    @Test
    void agentFailureAppendsFallbackCardWithDoctorAdvice() {
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍饮食"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        when(agentClient.interpretVision(anyList(), any(), eq("DIET")))
                .thenThrow(new AgentClient.VisionAgentException("VISION_MODEL_TIMEOUT", 504, "超时"));
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        DietPhotoService service = new DietPhotoService(
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
        assertThatThrownBy(() -> service.analyze(12L, null, "diet-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(504);
                    assertThat(error.getCode()).isEqualTo("VISION_MODEL_TIMEOUT");
                });
        // 失败时仍回落一条 diet_analysis 兜底卡片，need_doctor=true 引导就医
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq("diet_analysis"), any(), any(), any());
    }

    @Test
    void nonImageFileIsRejectedBeforeAnyCall() {
        DietPhotoService service = new DietPhotoService(
                mock(ConversationService.class),
                mock(AgentClient.class),
                objectMapper,
                TestContracts.instance(),
                mock(HealthProfileService.class),
                TestDisclaimers.instance(),
                mock(MinioStorageService.class));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        assertThatThrownBy(() -> service.analyze(12L, null, "diet-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(422);
                });
    }
}
