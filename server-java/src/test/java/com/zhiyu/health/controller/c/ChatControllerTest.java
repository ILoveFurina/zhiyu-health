package com.zhiyu.health.controller.c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import com.zhiyu.health.service.ChatService;
import com.zhiyu.health.service.ConversationService;
import com.zhiyu.health.service.HealthProfileService;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/** 对话链路骨架：server-py 的 SSE 事件原样透传，且 event 行先于 data 行（小程序端按行序解析） */
class ChatControllerTest {

    @Test
    void redFlagInterruptsWithoutCallingAgent() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation(7L, 12L, "我突然胸痛，还出冷汗");
        Message warning = new Message(9L, 7L, "assistant", "red_flag", "警告", null);
        when(conversations.getOrCreateForPatient(12L, null, "我突然胸痛，还出冷汗")).thenReturn(conversation);
        when(conversations.appendMessage(eq(7L), eq("assistant"), anyString(), eq("red_flag"), isNull()))
                .thenReturn(warning);
        ChatService service = new ChatService(
                agentClient,
                conversations,
                new RedFlagRuleEngine(),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                TestContracts.instance(),
                mock(HealthProfileService.class));
        MockMvc mvc = standaloneSetup(new ChatController(service))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\": \"我突然胸痛，还出冷汗\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:meta", "\"conversation_id\":7");
        assertThat(body).contains("event:red_flag", "120", "胸痛");
        assertThat(body).doesNotContain("disclaimer");
    }

    @Test
    void chatPassesThroughSseEventsWithNameBeforeData() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, null, "你好")).thenReturn(new Conversation(7L, 12L, "你好"));
        when(conversations.recentContext(7L))
                .thenReturn(java.util.List.of(java.util.Map.of("role", "user", "content", "你好")));
        when(conversations.appendMessage(7L, "assistant", "请问哪里不舒服？", "text", "low"))
                .thenReturn(new Message(10L, 7L, "assistant", "text", "请问哪里不舒服？", "low"));
        when(conversations.appendMessage(eq(7L), eq("assistant"), anyString(), eq("doctor_recommendations"), isNull()))
                .thenReturn(new Message(11L, 7L, "assistant", "doctor_recommendations", "{}", null));
        when(conversations.appendMessage(eq(7L), eq("assistant"), anyString(), eq("appointment"), isNull()))
                .thenReturn(new Message(12L, 7L, "assistant", "appointment", "{}", null));
        when(agentClient.chat(anyMap()))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"effort\": \"low\"}")
                                .event("meta")
                                .build(),
                        ServerSentEvent.builder("{\"text\": \"你好\"}")
                                .event("token")
                                .build(),
                        // 结构化推荐同属 AI 产出，也必须由业务后端出口兜底免责声明
                        ServerSentEvent.builder("{\"doctors\":[{\"doctor_id\":2,\"name\":\"周安宁\"}]}")
                                .event("doctor_recommendations")
                                .build(),
                        ServerSentEvent.builder("{\"appointment_id\":21,\"notice\":\"病情摘要已发送给医生\"}")
                                .event("appointment")
                                .build(),
                        // 故意省略 disclaimer，验证业务后端出口兜底
                        ServerSentEvent.builder("{\"role\":\"assistant\",\"content\":\"请问哪里不舒服？\",\"effort\":\"low\"}")
                                .event("message")
                                .build(),
                        ServerSentEvent.builder("{}").event("done").build()));
        ChatService service = new ChatService(
                agentClient,
                conversations,
                new RedFlagRuleEngine(),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                TestContracts.instance(),
                mock(HealthProfileService.class));
        MockMvc mvc = standaloneSetup(new ChatController(service))
                // 对齐生产：Boot 自动配置的 StringHttpMessageConverter 默认 UTF-8，
                // standalone MockMvc 的默认转换器是 ISO-8859-1，需显式指定
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\": \"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:meta", "\"effort\":\"low\"", "\"conversation_id\":7");
        assertThat(body).contains("event:token", "\"text\":\"你好\"");
        int cardStart = body.indexOf("event:doctor_recommendations");
        int cardEnd = body.indexOf("event:message", cardStart);
        assertThat(body.substring(cardStart, cardEnd)).contains("\"doctor_id\":2", "仅供参考，不替代医生诊断", "\"message_id\":11");
        assertThat(body).contains("event:appointment", "病情摘要已发送给医生", "\"message_id\":12");
        assertThat(body).contains("event:message", "仅供参考，不替代医生诊断", "\"message_id\":10");
        assertThat(body).contains("event:done");
    }

    @Test
    void interpretationScenarioIsPassedThroughForAutoEffort() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, null, "帮我解读报告")).thenReturn(new Conversation(8L, 12L, "帮我解读报告"));
        when(conversations.recentContext(8L))
                .thenReturn(java.util.List.of(java.util.Map.of("role", "user", "content", "帮我解读报告")));
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        when(healthProfiles.current(12L))
                .thenReturn(new HealthProfileService.ProfileView(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", true, java.util.List.of("青霉素")));
        when(agentClient.chat(org.mockito.ArgumentMatchers.argThat(body -> "interpretation".equals(body.get("scenario"))
                        && body.get("health_profile").toString().contains("青霉素"))))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"effort\":\"high\"}")
                                .event("meta")
                                .build(),
                        ServerSentEvent.builder("{}").event("done").build()));
        ChatService service = new ChatService(
                agentClient,
                conversations,
                new RedFlagRuleEngine(),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                TestContracts.instance(),
                healthProfiles);
        MockMvc mvc = standaloneSetup(new ChatController(service))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\":\"帮我解读报告\",\"effort\":\"auto\"," + "\"scenario\":\"interpretation\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:meta", "\"effort\":\"high\"");
    }

    @Test
    void locationIsForwardedToAgentAndHospitalCardIsPersisted() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, null, "附近有什么医院"))
                .thenReturn(new Conversation(7L, 12L, "附近有什么医院"));
        when(conversations.recentContext(7L))
                .thenReturn(java.util.List.of(java.util.Map.of("role", "user", "content", "附近有什么医院")));
        when(conversations.appendMessage(
                        eq(7L), eq("assistant"), anyString(), eq("hospital_recommendations"), isNull()))
                .thenReturn(new Message(13L, 7L, "assistant", "hospital_recommendations", "{}", null));
        when(agentClient.chat(org.mockito.ArgumentMatchers.argThat(
                        body -> body.get("longitude").equals(121.4737)
                                && body.get("latitude").equals(31.2304))))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"effort\": \"low\"}")
                                .event("meta")
                                .build(),
                        ServerSentEvent.builder("{\"hospitals\":[{\"hospital_id\":1,\"name\":\"智愈市人民医院\","
                                        + "\"distance_km\":0.0}]}")
                                .event("hospital_recommendations")
                                .build(),
                        ServerSentEvent.builder("{}").event("done").build()));
        ChatService service = new ChatService(
                agentClient,
                conversations,
                new RedFlagRuleEngine(),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                TestContracts.instance(),
                mock(HealthProfileService.class));
        MockMvc mvc = standaloneSetup(new ChatController(service))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\":\"附近有什么医院\",\"longitude\":121.4737," + "\"latitude\":31.2304}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains(
                        "event:hospital_recommendations",
                        "\"hospital_id\":1",
                        "智愈市人民医院",
                        "仅供参考，不替代医生诊断",
                        "\"message_id\":13");
    }

    @Test
    void followUpInSameConversationReceivesDeterministicReportContext() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, 9L, "这个指标需要复查吗")).thenReturn(new Conversation(9L, 12L, "看报告"));
        when(conversations.recentContext(9L))
                .thenReturn(java.util.List.of(
                        java.util.Map.of(
                                "role", "assistant", "content", "报告解读：血红蛋白偏低；血红蛋白 108（参考 115-150），关注级别 yellow"),
                        java.util.Map.of("role", "user", "content", "这个指标需要复查吗")));
        when(agentClient.chat(org.mockito.ArgumentMatchers.argThat(body ->
                        body.toString().contains("血红蛋白 108") && body.toString().contains("这个指标需要复查吗"))))
                .thenReturn(
                        Flux.just(ServerSentEvent.builder("{}").event("done").build()));
        ChatService service = new ChatService(
                agentClient,
                conversations,
                new RedFlagRuleEngine(),
                new ObjectMapper(),
                TestDisclaimers.instance(),
                TestContracts.instance(),
                mock(HealthProfileService.class));
        MockMvc mvc = standaloneSetup(new ChatController(service))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\":\"这个指标需要复查吗\",\"conversation_id\":9}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }
}
