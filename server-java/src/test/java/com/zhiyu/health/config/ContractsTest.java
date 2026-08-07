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
        // ADR-0024（票 17）：中医专属免责与通用文案并列，舌诊卡片叠加两条
        assertThat(contracts.disclaimer().tcmText()).isEqualTo("体质辨识仅供参考，不替代中医面诊");
    }

    @Test
    void sseEventProtocolIsComplete() {
        Contracts.SseEvents events = contracts.sseEvents();
        assertThat(events.streamEvents()).containsExactly("meta", "knowledge", "token", "message", "done");
        assertThat(events.redFlagEvent()).isEqualTo("red_flag");
        assertThat(events.knowledgeEvent()).isEqualTo("knowledge");
        assertThat(events.cardEvents()).hasSize(6);
        // 票 50：find_hospitals 工具移除，department_slots 卡由编排代码确定性产出、不经 LLM 工具调用
        assertThat(events.toolToEvent())
                .hasSize(4)
                .containsEntry("recommend_doctors", "doctor_recommendations")
                .containsEntry("get_doctor_slots", "doctor_slots")
                .containsEntry("create_appointment", "appointment")
                .containsEntry("get_appointment", "appointments")
                .doesNotContainKey("find_hospitals");
        assertThat(events.messageKinds())
                .hasSize(14)
                .contains(
                        "text",
                        "report_interpretation",
                        "skin_analysis",
                        "image",
                        "diet_analysis",
                        "tongue_analysis",
                        "department_slots")
                // 票 51（ADR-0028）：C 端 medication_info/medication_safety 双卡片出口已删除，
                // 说明书走流式文本，禁忌仅留 B 端开方链路
                .doesNotContain("medication_info", "medication_safety");
        assertThat(events.aiCardKinds()).hasSize(10);
        assertThat(events.eventToKind())
                .hasSize(7)
                .containsEntry("hospital_recommendations", "hospital_recommendations")
                .containsEntry("department_slots", "department_slots");
    }

    @Test
    void visionErrorCodesAndMessagesAreLoaded() {
        Contracts.VisionErrors errors = contracts.visionErrors();
        assertThat(errors.codes()).hasSize(16);
        assertThat(errors.messages())
                .hasSize(16)
                .containsEntry("VISION_MODEL_TIMEOUT", "报告解读服务响应超时")
                .containsEntry("VISION_OUTPUT_INVALID", "本次未能生成可靠的结构化解读，请重试")
                .containsEntry("VISION_REPORT_SCOPE_UNSUPPORTED", "请上传报告文字页，暂不支持原始医学影像诊断")
                .containsEntry("VISION_SKIN_SCOPE_UNSUPPORTED", "请上传清晰的皮肤照片，暂不支持医学影像或报告诊断")
                .containsEntry("VISION_DIET_SCOPE_UNSUPPORTED", "请上传清晰的饮食照片，暂不支持医学影像或报告诊断")
                .containsEntry("VISION_TONGUE_SCOPE_UNSUPPORTED", "请上传清晰的舌苔照片，暂不支持医学影像或报告诊断")
                .containsEntry("VISION_PILL_BOX_SCOPE_UNSUPPORTED", "请上传清晰的药盒照片，暂不支持医学影像或报告诊断")
                .containsEntry("VISION_PROFILE_INVALID", "请求信息无法解析，请重试")
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
        // 票 51/票 50：chat 信封可选字段（药品说明书流 / 科室号源失败重试）
        assertThat(realtime.chatOptionalFields()).containsExactly("medication_name", "retry_standard_department_id");
    }

    @Test
    void medicationKnowledgeContractIsLoaded() {
        // 票 51（ADR-0028）：C 端通用药品说明书流事件序列与话术钉死
        Contracts.MedicationKnowledge knowledge = contracts.medicationKnowledge();
        assertThat(knowledge.streamEvents()).containsExactly("token", "done");
        assertThat(knowledge.tokenEvent()).isEqualTo("token");
        assertThat(knowledge.doneEvent()).isEqualTo("done");
        assertThat(knowledge.consultProfessional()).isEqualTo("具体是否适用请咨询医生或药师");
        assertThat(knowledge.unknownDrug()).contains("未找到该药品");
    }

    @Test
    void guidedRegistrationContractIsLoaded() {
        // 票 50：智能导诊标准科室解析与科室号源卡常量来自契约单一事实源
        Contracts.GuidedRegistration guided = contracts.guidedRegistration();
        assertThat(guided.resolutionStatuses()).containsExactly("explicit_booking", "resolved", "ambiguous", "none");
        assertThat(guided.cardEvent()).isEqualTo("department_slots");
        assertThat(guided.cardStatuses()).containsExactly("ok", "failed");
        assertThat(guided.retryRequestField()).isEqualTo("retry_standard_department_id");
        assertThat(guided.summaryTemplates().keySet()).containsExactlyInAnyOrder("ok", "empty", "failed");
        assertThat(guided.summaryTemplates().get("ok")).contains("{department}");
        assertThat(guided.timeSlotLabels()).containsEntry("AM", "上午").containsEntry("PM", "下午");
        assertThat(guided.retryUserText()).isEqualTo("重新查询号源");
        // 卡事件名必须与 sse-events 的 card_events/message_kinds 同源一致
        assertThat(contracts.sseEvents().cardEvents()).contains(guided.cardEvent());
        assertThat(contracts.sseEvents().messageKinds()).contains(guided.cardEvent());
        // 重试字段必须与 chat-realtime 的 chat_optional_fields 一致
        assertThat(contracts.chatRealtime().chatOptionalFields()).contains(guided.retryRequestField());
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
        assertThat(contraindication.messages().get("safe_without_history")).contains("无法完整确认");
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

    @Test
    void appointmentCareContractIsLoaded() {
        // 票 43：就诊指引卡消息 type 与 content schema 来自契约单一事实源
        Contracts.AppointmentCare care = contracts.appointmentCare();
        assertThat(care.messageType()).isEqualTo("appointment_care");
        assertThat(care.title()).isEqualTo("就诊指引");
        assertThat(care.contentSchema())
                .containsExactly(
                        "greeting",
                        "hospital_name",
                        "department_name",
                        "doctor_name",
                        "schedule_time",
                        "address",
                        "floor",
                        "materials",
                        "precautions");
    }

    @Test
    void emotionContractIsLoaded() {
        // 票 44：三档情绪标注 + 默认值 + 安抚语映射来自契约单一事实源
        Contracts.Emotion emotion = contracts.emotion();
        assertThat(emotion.emotions()).containsExactly("calm", "anxious", "fearful");
        assertThat(emotion.defaultEmotion()).isEqualTo("calm");
        assertThat(emotion.carriedBy()).isEqualTo("message");
        // calm 无安抚语（映射缺省即无），anxious/fearful 各一条确定性文案
        assertThat(emotion.soothingTexts()).hasSize(2);
        assertThat(emotion.soothingTexts()).containsKey("anxious").containsKey("fearful");
        assertThat(emotion.soothingText("calm")).isNull();
        assertThat(emotion.soothingText("anxious")).isNotBlank();
        assertThat(emotion.soothingText("fearful")).contains("120");
        // 白名单校验：防脏值写入 messages.emotion
        assertThat(emotion.isKnown("calm")).isTrue();
        assertThat(emotion.isKnown("angry")).isFalse();
        assertThat(emotion.isKnown(null)).isFalse();
    }
}
