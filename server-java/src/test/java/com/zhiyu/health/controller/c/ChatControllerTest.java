package com.zhiyu.health.controller.c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.time.Duration;
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
        when(healthProfiles.agentContext(12L))
                .thenReturn(new HealthProfileService.AgentProfileContext(
                        31L, "妈妈", "女", java.time.LocalDate.parse("1962-05-08"), "母亲", java.util.List.of("青霉素")));
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

    /** 票 33 回归：多轮工具回调的长对话（卡片 ×3 + token 流）必须完整到达 done，且每张卡片都落库。 */
    @Test
    void longConversationWithToolCallbacksStreamsInOrderAndPersistsEveryCard() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, null, "帮我挂林知远医生的号"))
                .thenReturn(new Conversation(7L, 12L, "帮我挂林知远医生的号"));
        when(conversations.recentContext(7L))
                .thenReturn(java.util.List.of(
                        java.util.Map.of("role", "user", "content", "我胸闷两天了"),
                        java.util.Map.of("role", "assistant", "content", "建议挂心血管内科"),
                        java.util.Map.of("role", "user", "content", "帮我挂林知远医生的号")));
        when(conversations.appendMessage(eq(7L), eq("assistant"), anyString(), eq("doctor_recommendations"), isNull()))
                .thenReturn(new Message(11L, 7L, "assistant", "doctor_recommendations", "{}", null));
        when(conversations.appendMessage(eq(7L), eq("assistant"), anyString(), eq("doctor_slots"), isNull()))
                .thenReturn(new Message(12L, 7L, "assistant", "doctor_slots", "{}", null));
        when(conversations.appendMessage(eq(7L), eq("assistant"), anyString(), eq("appointment"), isNull()))
                .thenReturn(new Message(13L, 7L, "assistant", "appointment", "{}", null));
        when(conversations.appendMessage(7L, "assistant", "已为你挂号，明天上午见。", "text", "low"))
                .thenReturn(new Message(14L, 7L, "assistant", "text", "已为你挂号，明天上午见。", "low"));
        // 模拟长对话的真实节奏：事件间隔到达（工具回调期间 LLM 思考窗口无字节），
        // 中继不得在间隙或卡片处理处断流
        when(agentClient.chat(anyMap()))
                .thenReturn(Flux.just(
                                ServerSentEvent.builder("{\"effort\":\"low\"}")
                                        .event("meta")
                                        .build(),
                                ServerSentEvent.builder("{\"doctors\":[{\"doctor_id\":1,\"name\":\"林知远\"}]}")
                                        .event("doctor_recommendations")
                                        .build(),
                                ServerSentEvent.builder("{\"doctor_id\":1,\"slots\":[{\"schedule_id\":9}]}")
                                        .event("doctor_slots")
                                        .build(),
                                ServerSentEvent.builder("{\"appointment_id\":21}")
                                        .event("appointment")
                                        .build(),
                                ServerSentEvent.builder("{\"text\":\"已为\"}")
                                        .event("token")
                                        .build(),
                                ServerSentEvent.builder("{\"text\":\"你挂号\"}")
                                        .event("token")
                                        .build(),
                                ServerSentEvent.builder("{\"text\":\"，明天上午见。\"}")
                                        .event("token")
                                        .build(),
                                ServerSentEvent.builder(
                                                "{\"role\":\"assistant\",\"content\":\"已为你挂号，明天上午见。\",\"effort\":\"low\"}")
                                        .event("message")
                                        .build(),
                                ServerSentEvent.builder("{}").event("done").build())
                        .delayElements(Duration.ofMillis(10)));
        MockMvc mvc = standaloneSetup(new ChatController(new ChatService(
                        agentClient,
                        conversations,
                        new RedFlagRuleEngine(),
                        new ObjectMapper(),
                        TestDisclaimers.instance(),
                        TestContracts.instance(),
                        mock(HealthProfileService.class))))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\":\"帮我挂林知远医生的号\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 完整序列 meta → 卡片 ×3 → token ×3 → message → done 必须按序到达
        String[] expectedOrder = {
            "event:meta", "event:doctor_recommendations", "event:doctor_slots",
            "event:appointment", "\"text\":\"已为\"", "\"text\":\"你挂号\"",
            "\"text\":\"，明天上午见。\"", "event:message", "event:done"
        };
        int cursor = -1;
        for (String marker : expectedOrder) {
            int index = body.indexOf(marker);
            assertThat(index).as("缺少或乱序的事件：%s", marker).isGreaterThan(cursor);
            cursor = index;
        }
        assertThat(body).contains("\"message_id\":11", "\"message_id\":12", "\"message_id\":13", "\"message_id\":14");
        verify(conversations)
                .appendMessage(eq(7L), eq("assistant"), anyString(), eq("doctor_recommendations"), isNull());
        verify(conversations).appendMessage(eq(7L), eq("assistant"), anyString(), eq("doctor_slots"), isNull());
        verify(conversations).appendMessage(eq(7L), eq("assistant"), anyString(), eq("appointment"), isNull());
    }

    /** 票 33：上游在响应已提交后失败时，中继安静收尾——不得再触发 No converter 二次噪音。 */
    @Test
    void upstreamFailureMidStreamCompletesQuietly() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, null, "你好")).thenReturn(new Conversation(7L, 12L, "你好"));
        when(conversations.recentContext(7L))
                .thenReturn(java.util.List.of(java.util.Map.of("role", "user", "content", "你好")));
        when(agentClient.chat(anyMap()))
                .thenReturn(Flux.concat(
                        Flux.just(
                                ServerSentEvent.builder("{\"effort\":\"low\"}")
                                        .event("meta")
                                        .build(),
                                ServerSentEvent.builder("{\"text\":\"你\"}")
                                        .event("token")
                                        .build()),
                        Flux.error(new RuntimeException("upstream boom"))));
        MockMvc mvc = standaloneSetup(new ChatController(new ChatService(
                        agentClient,
                        conversations,
                        new RedFlagRuleEngine(),
                        new ObjectMapper(),
                        TestDisclaimers.instance(),
                        TestContracts.instance(),
                        mock(HealthProfileService.class))))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 已发送内容原样保留，流干净结束；错误细节只进服务端日志，不写进 SSE 通道
        assertThat(body).contains("event:meta", "\"text\":\"你\"");
        assertThat(body).doesNotContain("event:done", "upstream boom");
    }

    /** 票 33：响应未提交前上游即失败时，错误直达统一异常出口（端侧拿 HTTP 错误而非空 SSE）。 */
    @Test
    void upstreamFailureBeforeFirstEventSurfacesAsHttpError() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, null, "你好")).thenReturn(new Conversation(7L, 12L, "你好"));
        when(conversations.recentContext(7L))
                .thenReturn(java.util.List.of(java.util.Map.of("role", "user", "content", "你好")));
        when(agentClient.chat(anyMap())).thenReturn(Flux.error(new RuntimeException("connection refused")));
        MockMvc mvc = standaloneSetup(new ChatController(new ChatService(
                        agentClient,
                        conversations,
                        new RedFlagRuleEngine(),
                        new ObjectMapper(),
                        TestDisclaimers.instance(),
                        TestContracts.instance(),
                        mock(HealthProfileService.class))))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThatThrownBy(() -> mvc.perform(asyncDispatch(result))).hasRootCauseMessage("connection refused");
    }

    /** 票 33 主根因回归：卡片落库失败（如 kind 溢出）时中继留痕并安静收尾，不穿透 onNext 掐流。 */
    @Test
    void cardPersistenceFailureCompletesQuietlyWithoutCancellingUpstream() throws Exception {
        AgentClient agentClient = mock(AgentClient.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.getOrCreateForPatient(12L, null, "你好")).thenReturn(new Conversation(7L, 12L, "你好"));
        when(conversations.recentContext(7L))
                .thenReturn(java.util.List.of(java.util.Map.of("role", "user", "content", "你好")));
        // 模拟票 33 原始故障：首张卡片落库抛数据访问异常（原样穿透曾掐断整条流）
        when(conversations.appendMessage(eq(7L), eq("assistant"), anyString(), eq("doctor_recommendations"), isNull()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "value too long for type character varying(20)"));
        when(agentClient.chat(anyMap()))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"effort\":\"low\"}")
                                .event("meta")
                                .build(),
                        ServerSentEvent.builder("{\"doctors\":[{\"doctor_id\":1}]}")
                                .event("doctor_recommendations")
                                .build(),
                        ServerSentEvent.builder("{}").event("done").build()));
        MockMvc mvc = standaloneSetup(new ChatController(new ChatService(
                        agentClient,
                        conversations,
                        new RedFlagRuleEngine(),
                        new ObjectMapper(),
                        TestDisclaimers.instance(),
                        TestContracts.instance(),
                        mock(HealthProfileService.class))))
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult result = mvc.perform(post("/api/c/chat")
                        .requestAttr("authSubject", "12")
                        .contentType("application/json")
                        .content("{\"content\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // meta 已送达的部分保留；落库失败细节只进服务端日志，端侧拿到干净收尾而非异常噪音
        assertThat(body).contains("event:meta");
        assertThat(body).doesNotContain("event:done", "character varying");
    }
}
