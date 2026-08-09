package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.entity.chat.ChatRound;
import com.zhiyu.health.entity.chat.Message;
import com.zhiyu.health.mapper.chat.ChatRoundMapper;
import com.zhiyu.health.mapper.chat.MessageMapper;
import com.zhiyu.health.service.chat.ChatRoundPersistence;
import com.zhiyu.health.service.chat.ConversationService;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 票 78：购药两卡落库与回放一致性。drug_order_confirm 经 SSE persistEvent 路径落库（server-py 产出），
 * drug_order 结果卡由 server-java 下单成功后本地落库（不经 Agent）；两 kind 均走 ai_card_kind 分支，
 * content 存卡片 JSON、kind 列存对应值，message_id 注入、disclaimer 出口兜底。
 */
class DrugOrderCardPersistenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void drugOrderConfirmCardPersistedAsCardKindWithContentJson() throws Exception {
        // drug_order_confirm 确认卡：经 SSE 透传落 messages，kind=drug_order_confirm，
        // content=卡片 JSON（含 disclaimer 出口兜底），message_id 注入回事件
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round();
        JsonNode input = mapper.readTree("{\"source\":\"otc\",\"items\":[{\"medication_id\":7,\"quantity\":2}],"
                + "\"total_amount\":37.00,\"payable_amount\":37.00}");
        when(fixture.conversations.appendMessage(eq(7L), eq("assistant"), any(), eq("drug_order_confirm"), eq(null)))
                .thenReturn(fixture.savedMessage(101L));

        JsonNode result = fixture.persistence.persistEvent(round, "drug_order_confirm", input);

        // 出口兜底：disclaimer 注入（硬约束 1）
        assertThat(result.path("disclaimer").asText())
                .isEqualTo(TestContracts.instance().disclaimer().text());
        // request_id 透传 + message_id 注入
        assertThat(result.path("request_id").asText()).isEqualTo("req-confirm");
        assertThat(result.path("message_id").asLong()).isEqualTo(101L);
        // content 落库为完整卡片 JSON（含 source/items/total_amount/disclaimer）
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(fixture.conversations)
                .appendMessage(eq(7L), eq("assistant"), content.capture(), eq("drug_order_confirm"), eq(null));
        JsonNode stored = mapper.readTree(content.getValue());
        assertThat(stored.path("source").asText()).isEqualTo("otc");
        assertThat(stored.path("items").get(0).path("medication_id").asInt()).isEqualTo(7);
        assertThat(stored.path("total_amount").asDouble()).isEqualTo(37.00);
        assertThat(stored.path("disclaimer").asText())
                .isEqualTo(TestContracts.instance().disclaimer().text());
    }

    @Test
    void drugOrderResultCardPersistedAsCardKindWithOrderViewJson() throws Exception {
        // drug_order 结果卡：server-java 下单成功后本地落库（不经 SSE），复用同一 ai_card_kind 分支，
        // kind=drug_order、content=OrderView JSON、disclaimer 出口兜底
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round();
        JsonNode input = mapper.readTree("{\"id\":88,\"source\":\"otc\",\"status\":\"UNPAID\",\"status_label\":\"待支付\","
                + "\"total_amount\":37.00,\"items\":[{\"medication_id\":7,\"quantity\":2}],"
                + "\"cancellable\":true,\"payable\":true}");
        when(fixture.conversations.appendMessage(eq(7L), eq("assistant"), any(), eq("drug_order"), eq(null)))
                .thenReturn(fixture.savedMessage(202L));

        JsonNode result = fixture.persistence.persistEvent(round, "drug_order", input);

        assertThat(result.path("disclaimer").asText())
                .isEqualTo(TestContracts.instance().disclaimer().text());
        assertThat(result.path("message_id").asLong()).isEqualTo(202L);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(fixture.conversations)
                .appendMessage(eq(7L), eq("assistant"), content.capture(), eq("drug_order"), eq(null));
        JsonNode stored = mapper.readTree(content.getValue());
        assertThat(stored.path("id").asLong()).isEqualTo(88);
        assertThat(stored.path("status").asText()).isEqualTo("UNPAID");
        assertThat(stored.path("source").asText()).isEqualTo("otc");
        assertThat(stored.path("total_amount").asDouble()).isEqualTo(37.00);
        assertThat(stored.path("cancellable").asBoolean()).isTrue();
        assertThat(stored.path("disclaimer").asText())
                .isEqualTo(TestContracts.instance().disclaimer().text());
    }

    @Test
    void bothCardKindsAreAiCardKindsAndExcludedFromLlmContext() {
        // 两 kind 均为 ai_card_kind，落 messages 后历史回看复现卡片，且不进 LLM 上下文（卡片 JSON 非自然语言）
        assertThat(Message.isAiCardKind("drug_order_confirm")).isTrue();
        assertThat(Message.isAiCardKind("drug_order")).isTrue();
        // ConversationService.recentContext 排除集 = ai_card_kinds + report_upload + image，
        // 两卡自动被排除（避免卡片 JSON 塞回 LLM 上下文）
        assertThat(TestContracts.instance().sseEvents().aiCardKinds()).contains("drug_order_confirm", "drug_order");
    }

    private static final class Fixture {
        private final ChatRoundMapper roundMapper = mock(ChatRoundMapper.class);
        private final MessageMapper messageMapper = mock(MessageMapper.class);
        private final ConversationService conversations = mock(ConversationService.class);
        private final ChatRoundPersistence persistence = new ChatRoundPersistence(
                roundMapper,
                messageMapper,
                conversations,
                TestDisclaimers.instance(),
                TestContracts.instance(),
                new ObjectMapper());

        private ChatRound round() {
            ChatRound round = new ChatRound(12L, "req-confirm", 7L, 9L, "ACCEPTED");
            round.setId(34L);
            round.setRequestId("req-confirm");
            return round;
        }

        private Message savedMessage(long id) {
            Message message = new Message();
            message.setId(id);
            return message;
        }
    }
}
