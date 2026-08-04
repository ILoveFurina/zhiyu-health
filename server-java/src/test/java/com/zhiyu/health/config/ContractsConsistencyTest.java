package com.zhiyu.health.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiyu.health.entity.Message;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 契约消费一致性：代码实际使用值与 contracts/*.json 一致，重点钉死 Message 的 KIND_* 兼容壳。 */
class ContractsConsistencyTest {

    private final Contracts contracts = Contracts.load(Contracts.resolveDir());

    @Test
    void messageKindsFitDatabaseColumn() throws Exception {
        // 票 33：doctor_recommendations(22)/hospital_recommendations(24) 超出 messages.kind
        // VARCHAR(20)，卡片落库失败曾直接掐断 SSE 中继；契约 kind 必须始终装得下列宽。
        String schema = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("schema.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("kind\\s+VARCHAR\\((\\d+)\\)").matcher(schema);
        assertThat(matcher.find()).as("schema.sql 必须存在 kind VARCHAR(n) 列定义").isTrue();
        int columnWidth = Integer.parseInt(matcher.group(1));
        for (String kind : contracts.sseEvents().messageKinds()) {
            assertThat(kind.length())
                    .as("契约 kind %s 长度必须装进 messages.kind VARCHAR(%d)", kind, columnWidth)
                    .isLessThanOrEqualTo(columnWidth);
        }
        // red_flag 是规则引擎本地产物（不在契约 messageKinds 内），同样落 messages.kind
        assertThat(contracts.sseEvents().redFlagEvent().length()).isLessThanOrEqualTo(columnWidth);
    }

    @Test
    void messageKindConstantsMatchContractOrder() {
        Contracts.SseEvents events = contracts.sseEvents();
        // Message 按此顺序取下标赋值，顺序漂移会直接错位，必须钉死
        assertThat(events.messageKinds())
                .containsExactly(
                        "text",
                        "doctor_recommendations",
                        "doctor_slots",
                        "hospital_recommendations",
                        "appointment",
                        "appointments",
                        "report_upload",
                        "report_interpretation",
                        "report_context");
        assertThat(Message.KIND_TEXT).isEqualTo(events.messageKinds().get(0));
        assertThat(Message.KIND_DOCTOR_RECOMMENDATIONS)
                .isEqualTo(events.messageKinds().get(1));
        assertThat(Message.KIND_DOCTOR_SLOTS).isEqualTo(events.messageKinds().get(2));
        assertThat(Message.KIND_HOSPITAL_RECOMMENDATIONS)
                .isEqualTo(events.messageKinds().get(3));
        assertThat(Message.KIND_APPOINTMENT).isEqualTo(events.messageKinds().get(4));
        assertThat(Message.KIND_APPOINTMENTS).isEqualTo(events.messageKinds().get(5));
        assertThat(Message.KIND_REPORT_UPLOAD).isEqualTo(events.messageKinds().get(6));
        assertThat(Message.KIND_REPORT_INTERPRETATION)
                .isEqualTo(events.messageKinds().get(7));
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
        assertThat(events.streamEvents()).containsExactly("meta", "knowledge", "token", "message", "done");
        assertThat(events.metaEvent()).isEqualTo("meta");
        assertThat(events.knowledgeEvent()).isEqualTo("knowledge");
        assertThat(events.tokenEvent()).isEqualTo("token");
        assertThat(events.messageEvent()).isEqualTo("message");
        assertThat(events.doneEvent()).isEqualTo("done");
        assertThat(events.redFlagEvent()).isEqualTo("red_flag");
    }

    @Test
    void traceEventsAreDisjointFromCardAndStreamEvents() {
        // 票 24：trace 事件名集合必须与 card_events/ai_card_kinds 严格不相交，
        // 且不得与 done 重名（done 是轮次终止信号，trace 不得冒充）。
        Contracts.SseEvents events = contracts.sseEvents();
        assertThat(events.traceEvents()).containsExactly("tool_start", "tool_end");
        assertThat(events.toolStartEvent()).isEqualTo("tool_start");
        assertThat(events.toolEndEvent()).isEqualTo("tool_end");
        assertThat(events.isTraceEvent("tool_start")).isTrue();
        assertThat(events.isTraceEvent("tool_end")).isTrue();
        assertThat(events.isTraceEvent("token")).isFalse();
        assertThat(events.isTraceEvent("done")).isFalse();

        Set<String> cardAndKinds = new HashSet<>();
        cardAndKinds.addAll(events.cardEvents());
        cardAndKinds.addAll(events.aiCardKinds());
        cardAndKinds.add(events.redFlagEvent());
        cardAndKinds.addAll(events.streamEvents());
        for (String trace : events.traceEvents()) {
            assertThat(cardAndKinds)
                    .as("trace 事件 %s 不得与 card/kind/stream 事件重名", trace)
                    .doesNotContain(trace);
        }
        // tool_end 结果枚举白名单
        assertThat(events.traceResults()).containsExactly("success", "error", "skipped");
        assertThat(events.isTraceResult("success")).isTrue();
        assertThat(events.isTraceResult("error")).isTrue();
        assertThat(events.isTraceResult("skipped")).isTrue();
        assertThat(events.isTraceResult("ok")).isFalse();
        assertThat(events.traceErrorCodeUnknown()).isEqualTo("TOOL_ERROR_UNKNOWN");
    }

    @Test
    void llmContextExclusionIsAiCardKindsPlusReportUpload() {
        // ConversationService.recentContext 的排除集 = 契约 ai_card_kinds + report_upload
        Set<String> excluded = new HashSet<>(contracts.sseEvents().aiCardKinds());
        excluded.add(Message.KIND_REPORT_UPLOAD);
        assertThat(excluded)
                .containsExactlyInAnyOrder(
                        "doctor_recommendations",
                        "doctor_slots",
                        "hospital_recommendations",
                        "appointment",
                        "appointments",
                        "report_interpretation",
                        "report_upload");
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
        assertThat(contracts.visionErrors().messages()).containsEntry("VISION_MODEL_TIMEOUT", "报告解读服务响应超时");
    }

    @Test
    void prescriptionFlowValuesAreLoaded() {
        Contracts.PrescriptionFlow flow = contracts.prescriptionFlow();
        assertThat(flow.statuses())
                .containsEntry("pending", "PENDING")
                .containsEntry("approved", "APPROVED")
                .containsEntry("rejected", "REJECTED");
        assertThat(flow.decisions()).containsEntry("approve", "APPROVE").containsEntry("reject", "REJECT");
        assertThat(flow.messageTypes()).containsEntry("consultation_summary", "CONSULTATION_SUMMARY");
    }

    @Test
    void emotionValuesFitDatabaseColumn() throws Exception {
        // 票 44：契约 emotion 枚举必须装得下 messages.emotion VARCHAR(16) 列宽
        String schema = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("schema.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("emotion\\s+VARCHAR\\((\\d+)\\)").matcher(schema);
        assertThat(matcher.find()).as("schema.sql 必须存在 emotion VARCHAR(n) 列定义").isTrue();
        int columnWidth = Integer.parseInt(matcher.group(1));
        for (String emotion : contracts.emotion().emotions()) {
            assertThat(emotion.length())
                    .as("契约 emotion %s 长度必须装进 messages.emotion VARCHAR(%d)", emotion, columnWidth)
                    .isLessThanOrEqualTo(columnWidth);
        }
        // 默认值同样须在白名单内（降级 calm）
        assertThat(contracts.emotion().emotions()).contains(contracts.emotion().defaultEmotion());
    }

    @Test
    void voiceContractSkeletonIsLoaded() {
        // 票 45：骨架阶段 enabled=false、格式字段留 null；开通后只填值不改结构
        Contracts.Voice voice = contracts.voice();
        assertThat(voice.asrEnabled()).isFalse();
        assertThat(voice.ttsEnabled()).isFalse();
        assertThat(voice.asrFormat()).isNull();
        assertThat(voice.ttsFormat()).isNull();
        assertThat(voice.ttsVoice()).isNull();
        // 超时/最大时长占位值钉死（开通后可按火山产品形态调整）
        assertThat(voice.asrTimeoutMs()).isEqualTo(10000);
        assertThat(voice.asrMaxDurationMs()).isEqualTo(60000);
        assertThat(voice.ttsTimeoutMs()).isEqualTo(15000);
        // 错误码集合与降级提示必须存在（开通前后均需）
        assertThat(voice.errorCodes())
                .contains("VOICE_UNCONFIGURED", "VOICE_AUDIO_INVALID", "VOICE_MODEL_TIMEOUT", "VOICE_MODEL_FAILED");
        assertThat(voice.degradeHint()).contains("语音功能暂不可用");
        assertThat(voice.isKnownCode("VOICE_MODEL_TIMEOUT")).isTrue();
        assertThat(voice.isKnownCode("UNKNOWN")).isFalse();
    }
}
