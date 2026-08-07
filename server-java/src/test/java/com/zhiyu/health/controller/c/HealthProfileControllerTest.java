package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.controller.c.mapping.HealthProfileInputMapper;
import com.zhiyu.health.service.HealthProfileService;
import com.zhiyu.health.support.TestContracts;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 健康档案 C 端 HTTP seam。 */
class HealthProfileControllerTest {

    @Test
    void patientCreatesListsAndActivatesFamilyProfiles() throws Exception {
        HealthProfileService service = mock(HealthProfileService.class);
        HealthProfileService.ProfileView self = profile(31L, "安安", "本人", true, List.of("青霉素"));
        HealthProfileService.ProfileView mother = profile(32L, "妈妈", "母亲", true, List.of());
        when(service.list(7L)).thenReturn(List.of(self));
        when(service.create(org.mockito.ArgumentMatchers.any())).thenReturn(mother);
        when(service.activate(7L, 32L)).thenReturn(mother);
        when(service.replaceAllergies(7L, 31L, List.of("青霉素", "磺胺")))
                .thenReturn(profile(31L, "安安", "本人", true, List.of("青霉素", "磺胺")));
        MockMvc mvc = standaloneSetup(
                        new HealthProfileController(service, Mappers.getMapper(HealthProfileInputMapper.class)))
                .build();

        mvc.perform(get("/api/c/health-profiles").requestAttr("authSubject", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].display_name").value("安安"))
                .andExpect(jsonPath("$[0].allergies[0]").value("青霉素"))
                .andExpect(jsonPath("$[0].active").value(true));

        mvc.perform(
                        post("/api/c/health-profiles")
                                .requestAttr("authSubject", "7")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"display_name":"妈妈","gender":"女","birth_date":"1962-05-08",
                                 "relationship":"母亲","allergies":[]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(32))
                .andExpect(jsonPath("$.relationship").value("母亲"));

        mvc.perform(post("/api/c/health-profiles/32/activate").requestAttr("authSubject", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        verify(service).activate(7L, 32L);

        mvc.perform(put("/api/c/health-profiles/31/allergies")
                        .requestAttr("authSubject", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allergies\":[\"青霉素\",\"磺胺\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergies[1]").value("磺胺"));
    }

    @Test
    void patientReadsOnlySelectedProfilesTimeline() throws Exception {
        // 时间线五分支投影形状（票 55 新增 ONLINE_CONSULTATION 与在线处方条目）；
        // 在线问诊条目 type 钉住契约值，SQL 字面量与契约的一致性由 ContractsConsistencyTest 兜底。
        String onlineType =
                TestContracts.instance().onlineConsultation().timelineTypes().get("online_consultation");
        HealthProfileService service = mock(HealthProfileService.class);
        when(service.timeline(7L, 31L))
                .thenReturn(List.of(
                        new HealthProfileService.TimelineView(
                                "REPORT_INTERPRETATION",
                                41L,
                                "报告解读",
                                "血红蛋白偏低",
                                "2026-07-29T10:00:00+08:00",
                                "仅供参考，不替代医生诊断"),
                        new HealthProfileService.TimelineView(
                                "PRESCRIPTION",
                                43L,
                                "在线问诊处方",
                                "周安宁 · 已审核通过",
                                "2026-07-29T09:30:00+08:00",
                                "仅供参考，不替代医生诊断"),
                        new HealthProfileService.TimelineView(
                                onlineType, 44L, "在线问诊", "周安宁 · 呼吸内科", "2026-07-29T09:00:00+08:00", null),
                        new HealthProfileService.TimelineView(
                                "APPOINTMENT", 42L, "心血管内科挂号", "林知远 · 已约", "2026-07-28T09:00:00+08:00", null)));
        MockMvc mvc = standaloneSetup(
                        new HealthProfileController(service, Mappers.getMapper(HealthProfileInputMapper.class)))
                .build();

        mvc.perform(get("/api/c/health-profiles/31/timeline").requestAttr("authSubject", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("REPORT_INTERPRETATION"))
                .andExpect(jsonPath("$[0].disclaimer").value("仅供参考，不替代医生诊断"))
                .andExpect(jsonPath("$[1].type").value("PRESCRIPTION"))
                .andExpect(jsonPath("$[1].title").value("在线问诊处方"))
                .andExpect(jsonPath("$[2].type").value(onlineType))
                .andExpect(jsonPath("$[2].summary").value("周安宁 · 呼吸内科"))
                .andExpect(jsonPath("$[3].type").value("APPOINTMENT"));
        verify(service).timeline(7L, 31L);
    }

    private HealthProfileService.ProfileView profile(
            Long id, String name, String relationship, boolean active, List<String> allergies) {
        return new HealthProfileService.ProfileView(
                id, name, "女", LocalDate.of(1992, 5, 8), relationship, active, allergies);
    }
}
