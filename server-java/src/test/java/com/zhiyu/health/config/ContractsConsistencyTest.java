package com.zhiyu.health.config;

import com.zhiyu.health.entity.Message;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 契约消费一致性：代码实际使用值与 contracts/*.json 一致，重点钉死 Message 的 KIND_* 兼容壳。 */
class ContractsConsistencyTest {

    private final Contracts contracts = Contracts.load(Contracts.resolveDir());

    @Test
    void messageKindConstantsMatchContractOrder() {
        Contracts.SseEvents events = contracts.sseEvents();
        // Message 按此顺序取下标赋值，顺序漂移会直接错位，必须钉死
        assertThat(events.messageKinds())
                .containsExactly(
                        "text", "doctor_recommendations", "doctor_slots", "hospital_recommendations",
                        "appointment", "appointments", "report_upload", "report_interpretation", "report_context");
        assertThat(Message.KIND_TEXT).isEqualTo(events.messageKinds().get(0));
        assertThat(Message.KIND_DOCTOR_RECOMMENDATIONS).isEqualTo(events.messageKinds().get(1));
        assertThat(Message.KIND_DOCTOR_SLOTS).isEqualTo(events.messageKinds().get(2));
        assertThat(Message.KIND_HOSPITAL_RECOMMENDATIONS).isEqualTo(events.messageKinds().get(3));
        assertThat(Message.KIND_APPOINTMENT).isEqualTo(events.messageKinds().get(4));
        assertThat(Message.KIND_APPOINTMENTS).isEqualTo(events.messageKinds().get(5));
        assertThat(Message.KIND_REPORT_UPLOAD).isEqualTo(events.messageKinds().get(6));
        assertThat(Message.KIND_REPORT_INTERPRETATION).isEqualTo(events.messageKinds().get(7));
        assertThat(Message.KIND_REPORT_CONTEXT).isEqualTo(events.messageKinds().get(8));
    }

    @Test
    void aiCardKindPredicateCoversExactlyContractList() {
        for (String kind : contracts.sseEvents().aiCardKinds()) {
            assertThat(Message.isAiCardKind(kind)).isTrue();
        }
        assertThat(Message.isAiCardKind(Message.KIND_TEXT)).isFalse();
        assertThat(Message.isAiCardKind(Message.KIND_REPORT_UPLOAD)).isFalse();
        assertThat(Message.isAiCardKind(Message.KIND_REPORT_CONTEXT)).isFalse();
        // red_flag 是规则引擎产物，不属于 AI 卡片
        assertThat(Message.isAiCardKind("red_flag")).isFalse();
    }

    @Test
    void streamEventAccessorsMatchContractOrder() {
        Contracts.SseEvents events = contracts.sseEvents();
        assertThat(events.streamEvents()).containsExactly("meta", "token", "message", "done");
        assertThat(events.metaEvent()).isEqualTo("meta");
        assertThat(events.tokenEvent()).isEqualTo("token");
        assertThat(events.messageEvent()).isEqualTo("message");
        assertThat(events.doneEvent()).isEqualTo("done");
        assertThat(events.redFlagEvent()).isEqualTo("red_flag");
    }

    @Test
    void llmContextExclusionIsAiCardKindsPlusReportUpload() {
        // ConversationService.recentContext 的排除集 = 契约 ai_card_kinds + report_upload
        Set<String> excluded = new HashSet<>(contracts.sseEvents().aiCardKinds());
        excluded.add(Message.KIND_REPORT_UPLOAD);
        assertThat(excluded)
                .containsExactlyInAnyOrder(
                        "doctor_recommendations", "doctor_slots", "hospital_recommendations",
                        "appointment", "appointments", "report_interpretation", "report_upload");
    }

    @Test
    void uploadTypeAccessorsMatchContract() {
        Contracts.UploadLimits limits = contracts.uploadLimits();
        assertThat(limits.pdfType()).isEqualTo("application/pdf");
        assertThat(limits.imageTypes()).containsExactly("image/jpeg", "image/png");
    }

    @Test
    void agentClientLocalCodesAreConsistentWithContract() {
        // 超时码在契约内；服务不可达是本端兜底码，不得在契约白名单内
        assertThat(contracts.visionErrors().codes())
                .contains("VISION_MODEL_TIMEOUT")
                .doesNotContain("VISION_AGENT_UNAVAILABLE");
        assertThat(contracts.visionErrors().messages())
                .containsEntry("VISION_MODEL_TIMEOUT", "报告解读服务响应超时");
    }
}
