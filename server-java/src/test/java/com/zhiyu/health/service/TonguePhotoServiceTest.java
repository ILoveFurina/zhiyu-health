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

/** 拍舌苔中医辨证编排：图片旁路持久化、卡片回落、ADR-0024 双免责叠加与失败兜底话术。 */
class TonguePhotoServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyzePersistsPhotosThenCardAndReturnsViewWithDualDisclaimer() throws Exception {
        // ADR-0024 第 2 条：舌诊卡片叠加通用免责 + 中医专属免责两条
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍舌苔"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        JsonNode result = objectMapper.readTree(
                """
                {"constitution":"气虚质","tongue_features":"舌质淡红舌体胖大",\
                "care_direction":"规律作息可佐山药红枣","diet_principle":"少食生冷",\
                "urgency_hint":"","need_doctor":false}
                """);
        when(agentClient.interpretVision(anyList(), any(), eq("TONGUE")))
                .thenReturn(new AgentClient.VisionResponse(result, "仅供参考，不替代医生诊断", 1));
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        TonguePhotoService service = new TonguePhotoService(
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
        TonguePhotoService.TongueAnalysisView view = service.analyze(12L, null, "tongue-001", List.of(file));

        assertThat(view.conversationId()).isEqualTo(7L);
        assertThat(view.result().path("constitution").asText()).isEqualTo("气虚质");
        // 通用免责（硬约束 1）
        assertThat(view.disclaimer()).isEqualTo("仅供参考，不替代医生诊断");
        // ADR-0024：中医专属免责也挂载
        assertThat(view.tcmDisclaimer()).isEqualTo("体质辨识仅供参考，不替代中医面诊");
        // 图片旁路持久化先行，分析卡片随后；顺序钉死以保证"先留图后分析"
        InOrder order = inOrder(minioStorage, conversations, agentClient);
        order.verify(minioStorage).persistPhotosAndMessages(eq(7L), anyList());
        order.verify(agentClient).interpretVision(anyList(), any(), eq("TONGUE"));
        // tongue_analysis 卡片以 assistant 角色回落
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq("tongue_analysis"), any(), any(), any());
    }

    @Test
    void agentFailureAppendsFallbackCardWithSoftDoctorAdvice() {
        // ADR-0024 第 3 条：分析失败软兜底，回落 tongue_analysis 卡片引导就医，不扩红线引擎
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍舌苔"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        when(agentClient.interpretVision(anyList(), any(), eq("TONGUE")))
                .thenThrow(new AgentClient.VisionAgentException("VISION_MODEL_TIMEOUT", 504, "超时"));
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        TonguePhotoService service = new TonguePhotoService(
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
        assertThatThrownBy(() -> service.analyze(12L, null, "tongue-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(504);
                    assertThat(error.getCode()).isEqualTo("VISION_MODEL_TIMEOUT");
                });
        // 失败时仍回落一条 tongue_analysis 兜底卡片，need_doctor=true 软兜底引导就医
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq("tongue_analysis"), any(), any(), any());
    }

    @Test
    void nonImageFileIsRejectedBeforeAnyCall() {
        TonguePhotoService service = new TonguePhotoService(
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
        assertThatThrownBy(() -> service.analyze(12L, null, "tongue-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(422);
                });
    }
}
