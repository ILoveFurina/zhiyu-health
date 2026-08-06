package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.service.MedicationLookupService.MedicationLookupView;
import com.zhiyu.health.support.TestContracts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.multipart.MultipartFile;

/** 拍药盒照片编排（票 14，ADR-0025）：图片旁路持久化、vision 提名、双出口委托与失败兜底。 */
class PillBoxPhotoServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyzePersistsPhotosThenVisionThenDualOutput() throws Exception {
        // 链路：MinIO 旁路 -> vision(PILL_BOX) 提候选药名 -> MedicationLookupService 双出口
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍药盒"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        JsonNode visionResult = objectMapper.readTree(
                """
                {"candidates":[{"name":"阿莫西林胶囊"},\
                {"name":"阿莫西林"}],\
                "unreadable_hint":""}""");
        when(agentClient.interpretVision(anyList(), any(), eq("PILL_BOX")))
                .thenReturn(new AgentClient.VisionResponse(visionResult, "仅供参考，不替代医生诊断", "", 1));
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        MedicationLookupService medicationLookup = mock(MedicationLookupService.class);
        when(medicationLookup.lookupAndAppend(eq(12L), eq(7L), eq("拍药盒"), anyList()))
                .thenReturn(new MedicationLookupView(
                        7L,
                        objectMapper.readTree("{\"medications\":[{\"name\":\"阿莫西林胶囊\"}]}"),
                        objectMapper.readTree("{\"decision\":\"SAFE\",\"blocked\":false}"),
                        false,
                        null,
                        null,
                        "仅供参考，不替代医生诊断"));
        PillBoxPhotoService service = new PillBoxPhotoService(
                conversations,
                agentClient,
                objectMapper,
                TestContracts.instance(),
                healthProfiles,
                minioStorage,
                medicationLookup);

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        MedicationLookupView view = service.analyze(12L, null, "pill-001", List.of(file));

        assertThat(view.conversationId()).isEqualTo(7L);
        assertThat(view.notFound()).isFalse();
        assertThat(view.medicationInfo().path("medications").get(0).path("name").asText())
                .isEqualTo("阿莫西林胶囊");
        assertThat(view.medicationSafety().path("decision").asText()).isEqualTo("SAFE");
        // 图片旁路持久化先行，vision 随后，双出口委托最后
        InOrder order = inOrder(minioStorage, agentClient, medicationLookup);
        order.verify(minioStorage).persistPhotosAndMessages(eq(7L), anyList());
        order.verify(agentClient).interpretVision(anyList(), any(), eq("PILL_BOX"));
        order.verify(medicationLookup).lookupAndAppend(eq(12L), eq(7L), eq("拍药盒"), anyList());
    }

    @Test
    void analyzeWithoutActiveProfilePassesNullProfileAndCompletesDualOutput() throws Exception {
        // 票 46 回归：无激活健康档案是合法业务状态。agentContext 返回 null 时必须原样透传
        // （由 AgentClient 省略 health_profile part），流程不得 502，仍走 vision 提名 + 双出口。
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍药盒"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        JsonNode visionResult = objectMapper.readTree(
                """
                {"candidates":[{"name":"阿莫西林胶囊"}],\
                "unreadable_hint":""}""");
        when(agentClient.interpretVision(anyList(), any(), eq("PILL_BOX")))
                .thenReturn(new AgentClient.VisionResponse(visionResult, "仅供参考，不替代医生诊断", "", 1));
        // 无档案：agentContext 按设计返回 null（mock 默认即 null，显式桩出以表意）
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L)).thenReturn(null);
        MedicationLookupService medicationLookup = mock(MedicationLookupService.class);
        when(medicationLookup.lookupAndAppend(eq(12L), eq(7L), eq("拍药盒"), anyList()))
                .thenReturn(new MedicationLookupView(
                        7L,
                        objectMapper.readTree("{\"medications\":[{\"name\":\"阿莫西林胶囊\"}]}"),
                        objectMapper.readTree("{\"decision\":\"SAFE\",\"blocked\":false}"),
                        false,
                        null,
                        null,
                        "仅供参考，不替代医生诊断"));
        PillBoxPhotoService service = new PillBoxPhotoService(
                conversations,
                agentClient,
                objectMapper,
                TestContracts.instance(),
                healthProfiles,
                mock(MinioStorageService.class),
                medicationLookup);

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        MedicationLookupView view = service.analyze(12L, null, "pill-noprofile", List.of(file));

        assertThat(view.notFound()).isFalse();
        assertThat(view.medicationInfo().path("medications").get(0).path("name").asText())
                .isEqualTo("阿莫西林胶囊");
        assertThat(view.medicationSafety().path("decision").asText()).isEqualTo("SAFE");
        // 关键断言：null 档案原样透传给 AgentClient，由它决定省略 multipart part
        verify(agentClient).interpretVision(anyList(), isNull(), eq("PILL_BOX"));
    }

    @Test
    void emptyCandidatesAppendsTextHintWithoutLookup() throws Exception {
        // vision 未识别到药名（多药混拍/文字模糊）：落 text 消息，不调 MedicationLookupService
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍药盒"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        JsonNode visionResult =
                objectMapper.readTree("""
                {"candidates":[],"unreadable_hint":"文字模糊，无法可靠识别药名"}""");
        when(agentClient.interpretVision(anyList(), any(), eq("PILL_BOX")))
                .thenReturn(new AgentClient.VisionResponse(visionResult, "仅供参考，不替代医生诊断", "", 1));
        MedicationLookupService medicationLookup = mock(MedicationLookupService.class);
        PillBoxPhotoService service = new PillBoxPhotoService(
                conversations,
                agentClient,
                objectMapper,
                TestContracts.instance(),
                mock(HealthProfileService.class),
                mock(MinioStorageService.class),
                medicationLookup);

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        MedicationLookupView view = service.analyze(12L, null, "pill-001", List.of(file));

        assertThat(view.notFound()).isTrue();
        // 响应携带 hint 供前端展示后端已落库的引导文案
        assertThat(view.hint()).contains("未能识别药盒");
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq(Message.KIND_TEXT), any(), any(), any());
        // 不调 MedicationLookupService（无药名可查）
        verify(medicationLookup, org.mockito.Mockito.never()).lookupAndAppend(any(), any(), anyString(), anyList());
    }

    @Test
    void agentFailureAppendsFallbackTextHint() {
        // vision 失败时落 text 兜底引导重拍/查药品入口
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreateForPatient(eq(12L), any(), eq("拍药盒"))).thenReturn(conversation);
        AgentClient agentClient = mock(AgentClient.class);
        when(agentClient.interpretVision(anyList(), any(), eq("PILL_BOX")))
                .thenThrow(new AgentClient.VisionAgentException("VISION_MODEL_TIMEOUT", 504, "超时"));
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of()));
        PillBoxPhotoService service = new PillBoxPhotoService(
                conversations,
                agentClient,
                objectMapper,
                TestContracts.instance(),
                healthProfiles,
                mock(MinioStorageService.class),
                mock(MedicationLookupService.class));

        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        assertThatThrownBy(() -> service.analyze(12L, null, "pill-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(504);
                    assertThat(error.getCode()).isEqualTo("VISION_MODEL_TIMEOUT");
                });
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq(Message.KIND_TEXT), any(), any(), any());
    }

    @Test
    void nonImageFileIsRejectedBeforeAnyCall() {
        PillBoxPhotoService service = new PillBoxPhotoService(
                mock(ConversationService.class),
                mock(AgentClient.class),
                objectMapper,
                TestContracts.instance(),
                mock(HealthProfileService.class),
                mock(MinioStorageService.class),
                mock(MedicationLookupService.class));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getSize()).thenReturn(100L);
        when(file.isEmpty()).thenReturn(false);
        assertThatThrownBy(() -> service.analyze(12L, null, "pill-001", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(422);
                });
    }
}
