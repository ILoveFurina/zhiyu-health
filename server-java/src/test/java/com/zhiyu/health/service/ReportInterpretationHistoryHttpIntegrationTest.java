package com.zhiyu.health.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.controller.c.ReportInterpretationController;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import com.zhiyu.health.mapper.ReportInterpretationMapper;
import com.zhiyu.health.service.mapping.ReportInterpretationDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;

/** 报告历史 HTTP seam：患者隔离、倒序与免责声明出口兜底。 */
class ReportInterpretationHistoryHttpIntegrationTest {

    @Test
    void returnsOnlyAuthenticatedPatientsHistoryWithFixedDisclaimer() throws Exception {
        ReportInterpretation ownNewer = report(32L, 12L, "较新的报告", null);
        ReportInterpretation ownOlder = report(31L, 12L, "较早的报告", "被污染的免责声明");
        ReportInterpretation otherPatient = report(40L, 13L, "其他患者报告", null);
        List<ReportInterpretation> rows = List.of(ownNewer, otherPatient, ownOlder);
        ReportInterpretationMapper mapper = mock(ReportInterpretationMapper.class);
        when(mapper.selectHistoryByPatient(12L)).thenAnswer(invocation -> rows.stream()
                .filter(row -> row.getPatientId().equals(12L))
                .sorted((left, right) -> Long.compare(right.getId(), left.getId()))
                .toList());
        ReportInterpretationPersistence persistence = new ReportInterpretationPersistence(
                mapper,
                mock(ConversationService.class),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                mock(HealthProfileService.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationMapper.class),
                TestContracts.instance());
        ReportInterpretationService service = new ReportInterpretationService(
                persistence,
                mock(AgentClient.class),
                new ObjectMapper(),
                mock(ReportUploadStagingService.class),
                TestContracts.instance(),
                mock(HealthProfileService.class),
                TestDisclaimers.instance(),
                Mappers.getMapper(ReportInterpretationDtoMapper.class),
                mock(HealthObservationMapping.class),
                mock(HealthObservationService.class),
                mock(MinioStorageService.class));
        MockMvc mvc = standaloneSetup(
                        new ReportInterpretationController(service, mock(ReportUploadStagingService.class)))
                .build();

        mvc.perform(get("/api/c/report-interpretations").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].report_interpretation_id").value(32))
                .andExpect(jsonPath("$[1].report_interpretation_id").value(31))
                .andExpect(jsonPath("$[0].disclaimer").value("仅供参考，不替代医生诊断"))
                .andExpect(jsonPath("$[1].disclaimer").value("仅供参考，不替代医生诊断"));
    }

    private ReportInterpretation report(long id, long patientId, String summary, String disclaimer) {
        ReportInterpretation report = new ReportInterpretation();
        report.setId(id);
        report.setPatientId(patientId);
        report.setStatus("SUCCEEDED");
        report.setResultJson("{\"summary\":\"" + summary + "\"}");
        report.setDisclaimer(disclaimer);
        return report;
    }
}
