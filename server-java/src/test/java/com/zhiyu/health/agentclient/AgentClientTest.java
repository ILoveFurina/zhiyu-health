package com.zhiyu.health.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import com.zhiyu.health.service.health.HealthProfileService;
import com.zhiyu.health.support.TestContracts;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

/** 视觉接口 multipart 编码（票 46）：可选 health_profile part 的有/无由档案是否为 null 决定。 */
class AgentClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final AgentClient client = new AgentClient(
            WebClient.builder(), "http://localhost:9", "test-secret", objectMapper, TestContracts.instance());

    private MultipartFile image() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("box.jpg");
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getBytes()).thenReturn(new byte[] {1, 2, 3});
        return file;
    }

    @Test
    void nullHealthProfileOmitsHealthProfilePart() throws Exception {
        // 票 46 回归：无档案时不得发送 health_profile part；序列化 null 会得到字面 "null"，
        // server-py 无法按档案对象校验而 500，正是本票修复的跨栈编码错误。
        MultiValueMap<String, HttpEntity<?>> parts = client.buildVisionMultipart(List.of(image()), null, "PILL_BOX");

        assertThat(parts).containsKey("scenario");
        assertThat(parts).containsKey("files");
        assertThat(parts).doesNotContainKey("health_profile");
    }

    @Test
    void presentHealthProfileIsSerializedAsCompleteObject() throws Exception {
        HealthProfileService.AgentProfileContext profile = new HealthProfileService.AgentProfileContext(
                31L, "妈妈", "女", LocalDate.parse("1962-05-08"), "母亲", List.of("青霉素"));

        MultiValueMap<String, HttpEntity<?>> parts = client.buildVisionMultipart(List.of(image()), profile, "PILL_BOX");

        assertThat(parts).containsKey("health_profile");
        String json = (String) parts.getFirst("health_profile").getBody();
        var node = new ObjectMapper().readTree(json);
        assertThat(node.path("id").asLong()).isEqualTo(31L);
        assertThat(node.path("display_name").asText()).isEqualTo("妈妈");
        assertThat(node.path("allergies").get(0).asText()).isEqualTo("青霉素");
    }

    @Test
    void interpretVisionWithNullProfileSucceedsOverHttpWithoutHealthProfilePart() throws Exception {
        // 票 46 跨栈回归：无档案患者走真实 WebClient multipart 调用（stub 顶替 server-py），
        // 线上曾因此返回 502"药盒识别服务暂不可用"；这里断言请求不再携带 health_profile part
        // 且 200 响应正常解析，不再折叠为 502。
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/vision/interpret", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload =
                    """
                    {"result":{"candidates":[{"name":"阿莫西林胶囊"}],"unreadable_hint":""},\
                    "disclaimer":"仅供参考，不替代医生诊断","tcm_disclaimer":"","page_count":1}\
                    """
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            AgentClient httpClient = new AgentClient(
                    WebClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-secret",
                    objectMapper,
                    TestContracts.instance());

            AgentClient.VisionResponse response = httpClient.interpretVision(List.of(image()), null, "PILL_BOX");

            assertThat(response.result().path("candidates").get(0).path("name").asText())
                    .isEqualTo("阿莫西林胶囊");
            assertThat(receivedBody.get()).contains("name=\"scenario\"");
            assertThat(receivedBody.get()).doesNotContain("name=\"health_profile\"");
        } finally {
            server.stop(0);
        }
    }
}
