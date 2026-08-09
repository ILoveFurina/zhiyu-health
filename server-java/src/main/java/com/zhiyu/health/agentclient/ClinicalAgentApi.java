package com.zhiyu.health.agentclient;

import com.zhiyu.health.config.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/** 处方解释与接诊总结等非流式临床文本能力。 */
final class ClinicalAgentApi {
    private final WebClient webClient;

    ClinicalAgentApi(WebClient webClient) {
        this.webClient = webClient;
    }

    AgentClient.ClinicalResponse explainPrescription(List<Map<String, String>> items) {
        return generate("/api/agent/clinical/prescription-explanation", Map.of("items", items));
    }

    AgentClient.ClinicalResponse summarizeConsultation(String diagnosis, String advice) {
        return generate("/api/agent/clinical/consultation-summary", Map.of("diagnosis", diagnosis, "advice", advice));
    }

    private AgentClient.ClinicalResponse generate(String uri, Map<String, ?> body) {
        try {
            AgentClient.ClinicalResponse response = webClient
                    .post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(AgentClient.ClinicalResponse.class)
                    .block(Duration.ofSeconds(70));
            if (response == null
                    || response.content() == null
                    || response.content().isBlank()) {
                throw new ApiException(502, "AI 内容生成暂不可用");
            }
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(502, "AI 内容生成暂不可用");
        }
    }
}
