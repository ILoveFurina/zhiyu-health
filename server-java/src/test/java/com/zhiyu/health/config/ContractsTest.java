package com.zhiyu.health.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 跨栈契约基座：加载仓库根 contracts/（与 server-py 共享同一 JSON），核对关键值。 */
class ContractsTest {

    private final Contracts contracts = Contracts.load(Contracts.resolveDir());

    @Test
    void disclaimerMatchesAuthoritativeText() {
        assertThat(contracts.disclaimer().text()).isEqualTo("仅供参考，不替代医生诊断");
    }

    @Test
    void sseEventProtocolIsComplete() {
        Contracts.SseEvents events = contracts.sseEvents();
        assertThat(events.streamEvents()).containsExactly("meta", "knowledge", "token", "message", "done");
        assertThat(events.redFlagEvent()).isEqualTo("red_flag");
        assertThat(events.knowledgeEvent()).isEqualTo("knowledge");
        assertThat(events.cardEvents()).hasSize(5);
        assertThat(events.toolToEvent())
                .hasSize(5)
                .containsEntry("recommend_doctors", "doctor_recommendations")
                .containsEntry("get_doctor_slots", "doctor_slots")
                .containsEntry("find_hospitals", "hospital_recommendations")
                .containsEntry("create_appointment", "appointment")
                .containsEntry("get_appointment", "appointments");
        assertThat(events.messageKinds()).hasSize(9).contains("text", "report_interpretation");
        assertThat(events.aiCardKinds()).hasSize(6);
        assertThat(events.eventToKind())
                .hasSize(6)
                .containsEntry("hospital_recommendations", "hospital_recommendations");
    }

    @Test
    void visionErrorCodesAndMessagesAreLoaded() {
        Contracts.VisionErrors errors = contracts.visionErrors();
        assertThat(errors.codes()).hasSize(11);
        assertThat(errors.messages())
                .hasSize(11)
                .containsEntry("VISION_MODEL_TIMEOUT", "报告解读服务响应超时")
                .containsEntry("VISION_OUTPUT_INVALID", "本次未能生成可靠的结构化解读，请重试")
                .containsEntry("VISION_REPORT_SCOPE_UNSUPPORTED", "请上传报告文字页，暂不支持原始医学影像诊断")
                .containsEntry("VISION_FILE_TOO_LARGE", "报告文件超出处理限制，请拆分或压缩后上传");
        // 错误码集合与文案表必须一一对应。
        assertThat(errors.messages().keySet()).containsExactlyInAnyOrderElementsOf(errors.codes());
    }

    @Test
    void uploadLimitsMatchBothStacks() {
        Contracts.UploadLimits limits = contracts.uploadLimits();
        assertThat(limits.maxFileBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(limits.maxTotalBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(limits.minFiles()).isEqualTo(1);
        assertThat(limits.maxFiles()).isEqualTo(5);
        assertThat(limits.allowedTypes()).containsExactly("image/jpeg", "image/png", "application/pdf");
        assertThat(limits.pdfSingleFile()).isTrue();
    }

    @Test
    void chatDefaultsAndGeoRangesAreLoaded() {
        Contracts.ChatDefaults defaults = contracts.chatDefaults();
        assertThat(defaults.effortDefault()).isEqualTo("auto");
        assertThat(defaults.scenarioDefault()).isEqualTo("triage");
        assertThat(defaults.effortChoices()).containsExactly("auto", "quick", "deep");
        assertThat(defaults.scenarios()).containsExactly("triage", "interpretation");
        assertThat(defaults.longitudeMin()).isEqualTo(-180.0);
        assertThat(defaults.longitudeMax()).isEqualTo(180.0);
        assertThat(defaults.latitudeMin()).isEqualTo(-90.0);
        assertThat(defaults.latitudeMax()).isEqualTo(90.0);
    }

    @Test
    void realtimeEnvelopeAndRoundStatusesAreLoaded() {
        assertThat(contracts.chatRealtime().websocketPath()).isEqualTo("/api/c/chat/ws");
        assertThat(contracts.chatRealtime().envelopeTypes()).containsExactly("chat", "accepted", "event", "error");
        assertThat(contracts.chatRealtime().roundStatuses())
                .containsExactly("ACCEPTED", "RUNNING", "COMPLETED", "FAILED");
        // 命名访问器与契约顺序的映射钉死：消费侧一律经访问器取契约值，不得再硬编码字面量
        Contracts.ChatRealtime realtime = contracts.chatRealtime();
        assertThat(realtime.chatEnvelope()).isEqualTo("chat");
        assertThat(realtime.acceptedEnvelope()).isEqualTo("accepted");
        assertThat(realtime.eventEnvelope()).isEqualTo("event");
        assertThat(realtime.errorEnvelope()).isEqualTo("error");
        assertThat(realtime.acceptedStatus()).isEqualTo("ACCEPTED");
        assertThat(realtime.runningStatus()).isEqualTo("RUNNING");
        assertThat(realtime.completedStatus()).isEqualTo("COMPLETED");
        assertThat(realtime.failedStatus()).isEqualTo("FAILED");
    }

    @Test
    void missingContractsDirFailsFast() {
        assertThatThrownBy(() -> Contracts.load(Contracts.resolveDir().resolve("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("跨栈契约加载失败");
    }

    @Test
    void loadedCollectionsAreImmutable() {
        assertThatThrownBy(() -> contracts.sseEvents().cardEvents().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> contracts.visionErrors().messages().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contraindicationDecisionsAndMessagesAreLoaded() {
        Contracts.Contraindication contraindication = contracts.contraindication();
        assertThat(contraindication.decisions())
                .containsEntry("safe", "SAFE")
                .containsEntry("blocked", "BLOCKED")
                .containsEntry("review_required", "REVIEW_REQUIRED");
        assertThat(contraindication.messageTypes()).containsEntry("warning", "contraindication_warning");
        assertThat(contraindication.messages().get("blocked")).contains("请咨询医生或药师");
    }

    @Test
    void paymentFlowStatusesAndMessagesAreLoaded() {
        Contracts.PaymentFlow payment = contracts.paymentFlow();
        assertThat(payment.statuses()).containsExactlyInAnyOrderEntriesOf(Map.of("unpaid", "UNPAID", "paid", "PAID"));
        assertThat(payment.statusLabels()).containsEntry("UNPAID", "待支付").containsEntry("PAID", "已支付");
        assertThat(payment.decisions()).containsEntry("pay", "PAY");
        assertThat(payment.messages()).containsEntry("pay_success", "支付成功");
    }

    @Test
    void knowledgeContractValuesAreLoaded() {
        Contracts.Knowledge knowledge = contracts.knowledge();
        assertThat(knowledge.knowledgeSources()).containsExactly("rag", "graph");
        assertThat(knowledge.noneSource()).isEqualTo("none");
        assertThat(knowledge.defaultByScenario())
                .containsEntry("triage", "rag")
                .containsEntry("interpretation", "none");
        assertThat(knowledge.knowledgeMetaEvent()).isEqualTo("knowledge");
        assertThat(knowledge.knowledgeStatus()).containsExactly("ok", "degraded", "unavailable");
        assertThat(knowledge.embeddingDimension()).isEqualTo(2048);
        assertThat(knowledge.vectorColumn()).isEqualTo("vector");
        assertThat(knowledge.searchTopK()).isEqualTo(3);
        assertThat(knowledge.similarityThreshold()).isEqualTo(0.3);
    }

    @Test
    void orderFlowDefinesStatusesDecisionsAndMessages() {
        Contracts.OrderFlow flow = contracts.orderFlow();
        assertThat(flow.statuses())
                .containsEntry("unpaid", "UNPAID")
                .containsEntry("paid", "PAID")
                .containsEntry("done", "DONE")
                .containsEntry("cancelled", "CANCELLED");
        assertThat(flow.statusLabels()).containsEntry("UNPAID", "待支付");
        assertThat(flow.decisions())
                .containsEntry("pay", "PAY")
                .containsEntry("cancel", "CANCEL")
                .containsEntry("complete", "COMPLETE");
        assertThat(flow.messageTypes()).containsEntry("order_status", "DRUG_ORDER_STATUS");
        assertThat(flow.messages()).containsEntry("stock_insufficient", "药品库存不足，下单失败");
    }

    @Test
    void contractsDirCanBeOverridden() {
        // 解析顺序：系统属性 > 环境变量 > 默认 ../contracts；此处只断言默认值可用。
        assertThat(contracts.sseEvents().toolToEvent())
                .isEqualTo(Map.of(
                        "recommend_doctors", "doctor_recommendations",
                        "get_doctor_slots", "doctor_slots",
                        "find_hospitals", "hospital_recommendations",
                        "create_appointment", "appointment",
                        "get_appointment", "appointments"));
        assertThat(contracts.chatDefaults().effortChoices()).isEqualTo(List.of("auto", "quick", "deep"));
    }

    @Test
    void medCheckinFlowDefinesStatusesDecisionsAndTypes() {
        Contracts.MedCheckinFlow flow = contracts.medCheckinFlow();
        assertThat(flow.statuses()).containsEntry("pending", "PENDING").containsEntry("checked", "CHECKED");
        assertThat(flow.statusLabels()).containsEntry("PENDING", "待打卡").containsEntry("CHECKED", "已服用");
        assertThat(flow.decisions()).containsEntry("check", "CHECK");
        assertThat(flow.messageTypes()).containsEntry("medication_reminder", "MEDICATION_REMINDER");
        assertThat(flow.timelineTypes()).containsEntry("med_checkin", "MED_CHECKIN");
    }

    @Test
    void demoArsenalConstantsAreLoaded() {
        Contracts.DemoArsenal demo = contracts.demoArsenal();
        assertThat(demo.resetConfirmPhrase()).isEqualTo("DEMO_RESET_CONFIRM");
        assertThat(demo.knowledgeSourceValues()).containsExactly("rag", "graph", "none");
        assertThat(demo.knowledgeSourceDefault()).isEqualTo("none");
        assertThat(demo.knowledgeSourceRedisKey()).isEqualTo("demo:knowledge_source");
        // 基线数量与 seed.sql / deploy/neo4j/seed.cypher 对齐
        assertThat(demo.knowledgeBaselines())
                .containsEntry("knowledge_chunks", 50)
                .containsEntry("neo4j_symptoms", 50)
                .containsEntry("neo4j_diseases", 57)
                .containsEntry("neo4j_departments", 10)
                .containsEntry("neo4j_medications", 30)
                .containsEntry("neo4j_contraindications", 9);
        assertThat(demo.resetFreezeStatus()).isEqualTo(503);
        assertThat(demo.resetFreezeMessage()).isEqualTo("演示重置中，请稍后重试");
        // 值域与 knowledge.json 的 rag/graph + none 三态一致
        assertThat(demo.knowledgeSourceValues())
                .containsExactlyElementsOf(
                        List.of("rag", "graph", contracts.knowledge().noneSource()));
    }
}
