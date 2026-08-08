package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.service.mapping.ReportInterpretationDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.web.multipart.MultipartFile;

/** 报告解读编排：幂等命中时不得重复调用模型。 */
class ReportInterpretationServiceTest {

    @Test
    void listsOnlyPersistenceFilteredPatientHistoryInReturnedOrder() throws Exception {
        ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
        ReportInterpretation newer = new ReportInterpretation();
        newer.setId(32L);
        newer.setHealthProfileId(31L);
        newer.setStatus("SUCCEEDED");
        newer.setResultJson("{\"summary\":\"较新的报告\"}");
        newer.setDisclaimer(null);
        ReportInterpretation older = new ReportInterpretation();
        older.setId(31L);
        older.setHealthProfileId(30L);
        older.setStatus("SUCCEEDED");
        older.setResultJson("{\"summary\":\"较早的报告\"}");
        older.setDisclaimer("被污染的免责声明");
        when(persistence.listForPatient(12L)).thenReturn(List.of(newer, older));
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.list(12L))
                .thenReturn(List.of(
                        new HealthProfileService.ProfileView(
                                31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", false, List.of()),
                        new HealthProfileService.ProfileView(
                                30L, "本人", "男", java.time.LocalDate.parse("1992-05-12"), "本人", true, List.of())));
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                mock(AgentClient.class),
                new ObjectMapper(),
                mock(ReportUploadStagingService.class),
                TestContracts.instance(),
                healthProfiles,
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                mock(MinioStorageService.class));

        List<ReportInterpretationService.ReportView> result = service.listForPatient(12L);

        assertThat(result)
                .extracting(ReportInterpretationService.ReportView::reportInterpretationId)
                .containsExactly(32L, 31L);
        assertThat(result)
                .extracting(ReportInterpretationService.ReportView::profileName)
                .containsExactly("妈妈", "本人");
        assertThat(result)
                .extracting(ReportInterpretationService.ReportView::disclaimer)
                .containsOnly("仅供参考，不替代医生诊断");
        verify(persistence).listForPatient(12L);
    }

    @Test
    void succeededRequestReturnsStoredCardWithoutCallingAgentAgain() throws Exception {
        ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
        AgentClient agentClient = mock(AgentClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ReportInterpretation stored = new ReportInterpretation();
        stored.setId(31L);
        stored.setPatientId(12L);
        stored.setConversationId(7L);
        stored.setRequestId("req-001");
        stored.setStatus("SUCCEEDED");
        stored.setPageCount(1);
        stored.setResultJson("{\"summary\":\"已保存的解读\"}");
        stored.setDisclaimer("仅供参考，不替代医生诊断");
        when(persistence.findByRequest(12L, "req-001")).thenReturn(stored);
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                agentClient,
                objectMapper,
                mock(ReportUploadStagingService.class),
                TestContracts.instance(),
                mock(HealthProfileService.class),
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                mock(MinioStorageService.class));
        MultipartFile file = mock(MultipartFile.class);

        ReportInterpretationService.ReportView result = service.interpret(12L, null, "req-001", List.of(file));

        assertThat(result.reportInterpretationId()).isEqualTo(31L);
        assertThat(result.result().path("summary").asText()).isEqualTo("已保存的解读");
        verifyNoInteractions(agentClient, file);
    }

    @Test
    void newRequestPersistsAroundAgentCallWithoutWrappingNetworkInTransaction() throws Exception {
        ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
        AgentClient agentClient = mock(AgentClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ReportInterpretation processing = new ReportInterpretation();
        processing.setId(32L);
        processing.setPatientId(12L);
        processing.setConversationId(8L);
        processing.setRequestId("req-002");
        processing.setStatus("PROCESSING");
        processing.setHealthProfileId(31L);
        when(persistence.findByRequest(12L, "req-002")).thenReturn(null);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/png");
        when(persistence.start(12L, null, "req-002", List.of(file))).thenReturn(processing);
        AgentClient.VisionResponse vision = new AgentClient.VisionResponse(
                objectMapper.readTree(
                        """
                        {"summary":"血红蛋白偏低","items":[{"name":"血红蛋白",
                         "value":"108","reference_range":"115-150","unit":"g/L",
                         "priority":"yellow","explanation":"低于参考范围",
                         "action":"咨询医生","page":1}],"actions":["咨询医生"],"unreadable":[]}
                        """),
                "仅供参考，不替代医生诊断",
                null,
                1);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfileService.AgentProfileContext profile = new HealthProfileService.AgentProfileContext(
                31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of("青霉素"));
        when(healthProfiles.agentContext(12L, 31L)).thenReturn(profile);
        when(agentClient.interpretVision(List.of(file), profile, "REPORT")).thenReturn(vision);
        when(persistence.succeed(eq(processing), eq(vision), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    processing.setStatus("SUCCEEDED");
                    processing.setResultJson(invocation.getArgument(2));
                    processing.setContextSummary(invocation.getArgument(3));
                    processing.setPageCount(1);
                    processing.setDisclaimer("仅供参考，不替代医生诊断");
                    return processing;
                });
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                agentClient,
                objectMapper,
                mock(ReportUploadStagingService.class),
                TestContracts.instance(),
                healthProfiles,
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                mock(MinioStorageService.class));

        ReportInterpretationService.ReportView result = service.interpret(12L, null, "req-002", List.of(file));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().path("summary").asText()).isEqualTo("血红蛋白偏低");
        InOrder order = inOrder(persistence, agentClient);
        order.verify(persistence).start(12L, null, "req-002", List.of(file));
        order.verify(agentClient).interpretVision(List.of(file), profile, "REPORT");
        order.verify(persistence).succeed(eq(processing), eq(vision), anyString(), anyString());
    }

    @Test
    void finalizedRetryReturnsStoredResultWithoutTakingFilesAgain() throws Exception {
        ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
        ReportUploadStagingService staging = mock(ReportUploadStagingService.class);
        AgentClient agentClient = mock(AgentClient.class);
        ReportInterpretation stored = new ReportInterpretation();
        stored.setId(33L);
        stored.setConversationId(8L);
        stored.setStatus("SUCCEEDED");
        stored.setResultJson("{\"summary\":\"已保存\"}");
        stored.setDisclaimer("仅供参考，不替代医生诊断");
        when(persistence.findByRequest(12L, "req-retry")).thenReturn(stored);
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                agentClient,
                new ObjectMapper(),
                staging,
                TestContracts.instance(),
                mock(HealthProfileService.class),
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                mock(MinioStorageService.class));

        ReportInterpretationService.ReportView result = service.finalizeStaged(12L, 8L, "req-retry");

        assertThat(result.reportInterpretationId()).isEqualTo(33L);
        verify(staging).discard(12L, "req-retry");
        verifyNoInteractions(agentClient);
    }

    @Test
    void agentTimeoutIsPersistedAndReturnedWithDistinctCode() throws Exception {
        ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
        AgentClient agentClient = mock(AgentClient.class);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("image/png");
        ReportInterpretation processing = new ReportInterpretation();
        processing.setStatus("PROCESSING");
        processing.setHealthProfileId(31L);
        when(persistence.start(12L, null, "req-timeout", List.of(file))).thenReturn(processing);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfileService.AgentProfileContext profile = new HealthProfileService.AgentProfileContext(
                31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of("青霉素"));
        when(healthProfiles.agentContext(12L, 31L)).thenReturn(profile);
        when(agentClient.interpretVision(List.of(file), profile, "REPORT"))
                .thenThrow(new AgentClient.VisionAgentException("VISION_MODEL_TIMEOUT", 504, "报告解读服务响应超时"));
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                agentClient,
                new ObjectMapper(),
                mock(ReportUploadStagingService.class),
                TestContracts.instance(),
                healthProfiles,
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                mock(MinioStorageService.class));

        assertThatThrownBy(() -> service.interpret(12L, null, "req-timeout", List.of(file)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(504);
                    assertThat(error.getCode()).isEqualTo("VISION_MODEL_TIMEOUT");
                });
        verify(persistence).fail(processing, "VISION_MODEL_TIMEOUT");
    }

    @Test
    void imageFilesArePersistedToMinioAfterStartBeforeVisionCall() throws Exception {
        ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
        AgentClient agentClient = mock(AgentClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        ReportInterpretation processing = new ReportInterpretation();
        processing.setId(34L);
        processing.setPatientId(12L);
        processing.setConversationId(9L);
        processing.setRequestId("req-003");
        processing.setStatus("PROCESSING");
        processing.setHealthProfileId(31L);
        when(persistence.findByRequest(12L, "req-003")).thenReturn(null);
        MultipartFile front = mock(MultipartFile.class);
        when(front.getContentType()).thenReturn("image/png");
        MultipartFile back = mock(MultipartFile.class);
        when(back.getContentType()).thenReturn("image/jpeg");
        List<MultipartFile> files = List.of(front, back);
        when(persistence.start(12L, null, "req-003", files)).thenReturn(processing);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfileService.AgentProfileContext profile = new HealthProfileService.AgentProfileContext(
                31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of("青霉素"));
        when(healthProfiles.agentContext(12L, 31L)).thenReturn(profile);
        AgentClient.VisionResponse vision = new AgentClient.VisionResponse(
                objectMapper.readTree("{\"summary\":\"均在参考范围内\",\"items\":[],\"actions\":[],\"unreadable\":[]}"),
                "仅供参考，不替代医生诊断",
                null,
                2);
        when(agentClient.interpretVision(files, profile, "REPORT")).thenReturn(vision);
        when(persistence.succeed(eq(processing), eq(vision), anyString(), anyString()))
                .thenReturn(processing);
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                agentClient,
                objectMapper,
                mock(ReportUploadStagingService.class),
                TestContracts.instance(),
                healthProfiles,
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                minioStorage);

        ReportInterpretationService.ReportView result = service.interpret(12L, null, "req-003", files);

        assertThat(result.status()).isEqualTo("PROCESSING");
        // 图片在模型网络调用前先行落 MinIO + image 消息（分析失败时用户仍能回看"我拍过什么"）
        InOrder order = inOrder(persistence, minioStorage, agentClient);
        order.verify(persistence).start(12L, null, "req-003", files);
        order.verify(minioStorage).persistPhotosAndMessages(9L, files);
        order.verify(agentClient).interpretVision(files, profile, "REPORT");
    }

    @Test
    void singlePdfIsNotPersistedToMinio() throws Exception {
        ReportInterpretationPersistence persistence = mock(ReportInterpretationPersistence.class);
        AgentClient agentClient = mock(AgentClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        ReportInterpretation processing = new ReportInterpretation();
        processing.setId(35L);
        processing.setPatientId(12L);
        processing.setConversationId(10L);
        processing.setRequestId("req-pdf");
        processing.setStatus("PROCESSING");
        processing.setHealthProfileId(31L);
        when(persistence.findByRequest(12L, "req-pdf")).thenReturn(null);
        MultipartFile pdf = mock(MultipartFile.class);
        when(pdf.getContentType()).thenReturn("application/pdf");
        List<MultipartFile> files = List.of(pdf);
        when(persistence.start(12L, null, "req-pdf", files)).thenReturn(processing);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfileService.AgentProfileContext profile = new HealthProfileService.AgentProfileContext(
                31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", List.of("青霉素"));
        when(healthProfiles.agentContext(12L, 31L)).thenReturn(profile);
        AgentClient.VisionResponse vision = new AgentClient.VisionResponse(
                objectMapper.readTree("{\"summary\":\"pdf 报告\",\"items\":[],\"actions\":[],\"unreadable\":[]}"),
                "仅供参考，不替代医生诊断",
                null,
                1);
        when(agentClient.interpretVision(files, profile, "REPORT")).thenReturn(vision);
        when(persistence.succeed(eq(processing), eq(vision), anyString(), anyString()))
                .thenReturn(processing);
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                agentClient,
                objectMapper,
                mock(ReportUploadStagingService.class),
                TestContracts.instance(),
                healthProfiles,
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                minioStorage);

        service.interpret(12L, null, "req-pdf", files);

        // PDF 无法渲染为 image 气泡，不落 MinIO / 不写 image 消息，仅 report_upload 文本承载
        verify(minioStorage, never()).persistPhotosAndMessages(anyLong(), anyList());
    }
}
