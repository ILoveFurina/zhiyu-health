package com.zhiyu.health.controller.agent;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.AgentCallbackAuthFilter;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.service.HospitalRecommendationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Agent 业务工具回调 seam：按距离排序返回就近医院（票 49：院区粒度，附带 campus_name）。 */
@WebMvcTest(HospitalRecommendationController.class)
@Import(ApiExceptionHandler.class)
class HospitalRecommendationControllerTest {

    /** 与 src/test/resources/application.properties 的回调密钥一致 */
    private static final String CALLBACK_SECRET = "test-only-agent-callback-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalRecommendationService recommendationService;

    @Test
    void returnsNearbyHospitalsSortedByDistance() throws Exception {
        when(recommendationService.recommendNearby(113.6458, 34.7572))
                .thenReturn(List.of(
                        new HospitalRecommendationService.HospitalRecommendation(
                                1L, "郑州智愈综合医院", "三级甲等", "郑州市金水区健康路 88 号", "主院区", 0.0),
                        new HospitalRecommendationService.HospitalRecommendation(
                                2L, "郑州智愈儿童医院", "三级甲等", "郑州市中原区桐柏路 156 号", "西院区", 4.2)));

        mockMvc.perform(get("/api/agent/hospitals/nearby")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET)
                        .param("longitude", "113.6458")
                        .param("latitude", "34.7572"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hospitals.length()").value(2))
                .andExpect(jsonPath("$.hospitals[0].hospital_id").value(1))
                .andExpect(jsonPath("$.hospitals[0].name").value("郑州智愈综合医院"))
                .andExpect(jsonPath("$.hospitals[0].level").value("三级甲等"))
                .andExpect(jsonPath("$.hospitals[0].address").value("郑州市金水区健康路 88 号"))
                .andExpect(jsonPath("$.hospitals[0].campus_name").value("主院区"))
                .andExpect(jsonPath("$.hospitals[0].distance_km").value(0.0))
                .andExpect(jsonPath("$.hospitals[1].hospital_id").value(2))
                .andExpect(jsonPath("$.hospitals[1].campus_name").value("西院区"))
                .andExpect(jsonPath("$.hospitals[1].distance_km").value(4.2));
    }

    @Test
    void rejectsInvalidLongitude() throws Exception {
        mockMvc.perform(get("/api/agent/hospitals/nearby")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET)
                        .param("longitude", "200")
                        .param("latitude", "31.2304"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidLatitude() throws Exception {
        mockMvc.perform(get("/api/agent/hospitals/nearby")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET)
                        .param("longitude", "121.4737")
                        .param("latitude", "95"))
                .andExpect(status().isBadRequest());
    }
}
