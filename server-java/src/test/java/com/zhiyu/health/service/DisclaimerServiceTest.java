package com.zhiyu.health.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.support.TestDisclaimers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 免责声明挂载语义：文案取自契约、SSE 出口兜底幂等、独立字段按内容有无挂载。 */
class DisclaimerServiceTest {

    private final DisclaimerService disclaimers = TestDisclaimers.instance();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mountOverwritesMissingOrTamperedDisclaimer() {
        ObjectNode missing = objectMapper.createObjectNode().put("content", "你好");
        disclaimers.mount(missing);
        assertThat(missing.path("disclaimer").asText()).isEqualTo(disclaimers.text());

        ObjectNode tampered = objectMapper.createObjectNode().put("disclaimer", "已篡改为别的话");
        disclaimers.mount(tampered);
        assertThat(tampered.path("disclaimer").asText()).isEqualTo(disclaimers.text());
    }

    @Test
    void mountIsIdempotentForAlreadyAnnotatedCard() {
        ObjectNode card = objectMapper.createObjectNode().put("disclaimer", disclaimers.text());
        disclaimers.mount(card);
        assertThat(card.path("disclaimer").asText()).isEqualTo(disclaimers.text());
        // 幂等：重复挂载不产生重复字段或叠加文案。
        assertThat(card.properties()).hasSize(1);
    }

    @Test
    void mountIfPresentOnlyAnnotatesExistingContent() {
        assertThat(disclaimers.mountIfPresent("主诉胸闷两天")).isEqualTo(disclaimers.text());
        assertThat(disclaimers.mountIfPresent(null)).isNull();
    }
}
