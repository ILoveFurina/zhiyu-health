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
        assertThat(events.cardEvents()).hasSize(12);
        // 两类标准科室卡由主 Agent 的受控工具产出；find_hospitals 仍保持移除
        assertThat(events.toolToEvent())
                .hasSize(9)
                .containsEntry("recommend_doctors", "doctor_recommendations")
                .containsEntry("get_doctor_slots", "doctor_slots")
                .containsEntry("create_appointment", "appointment")
                .containsEntry("get_appointment", "appointments")
                .containsEntry("get_standard_department_slots", "department_slots")
                .containsEntry("suggest_standard_departments", "department_options")
                .containsEntry("search_medications", "medications")
                .containsEntry("list_approved_prescriptions", "prescriptions")
                .containsEntry("prepare_drug_order", "drug_order_prepare")
                .doesNotContainKey("find_hospitals");
        assertThat(events.messageKinds())
                .hasSize(20)
                .contains(
                        "text",
                        "report_interpretation",
                        "skin_analysis",
                        "image",
                        "diet_analysis",
                        "tongue_analysis",
                        "department_slots",
                        "department_options",
                        "medications",
                        "prescriptions",
                        "drug_order_prepare")
                // 票 51（ADR-0028）：C 端 medication_info/medication_safety 双卡片出口已删除，
                // 说明书走流式文本，禁忌仅留 B 端开方链路
                .doesNotContain("medication_info", "medication_safety");
        assertThat(events.aiCardKinds()).hasSize(16);
        // 票 78：购药确认卡（drug_order_confirm）与结果卡（drug_order）登记进
        // card_events/message_kinds/ai_card_kinds/event_to_kind 四集合
        assertThat(events.cardEvents()).contains("drug_order_confirm", "drug_order");
        assertThat(events.messageKinds()).contains("drug_order_confirm", "drug_order");
        assertThat(events.aiCardKinds()).contains("drug_order_confirm", "drug_order");
        assertThat(events.eventToKind())
                .hasSize(13)
                .containsEntry("hospital_recommendations", "hospital_recommendations")
                .containsEntry("department_slots", "department_slots")
                .containsEntry("department_options", "department_options")
                .containsEntry("medications", "medications")
                .containsEntry("prescriptions", "prescriptions")
                .containsEntry("drug_order_prepare", "drug_order_prepare")
                .containsEntry("drug_order_confirm", "drug_order_confirm")
                .containsEntry("drug_order", "drug_order");
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
        assertThat(limits.allowedTypes()).containsExactly("image/jpeg", "image/png", "image/webp", "application/pdf");
        assertThat(limits.pdfSingleFile()).isTrue();
    }

    @Test
    void doctorPhotoLimitsMatchBothStacks() {
        // 票 54：医生头像上传限制与报告上传不同（单张 2MB、仅 JPEG/PNG），钉死双栈一致
        Contracts.DoctorPhotoLimits limits = contracts.doctorPhotoLimits();
        assertThat(limits.maxBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(limits.maxFiles()).isEqualTo(1);
        assertThat(limits.allowedTypes()).containsExactly("image/jpeg", "image/png");
    }

    @Test
    void chatDefaultsAndGeoRangesAreLoaded() {
        Contracts.ChatDefaults defaults = contracts.chatDefaults();
        assertThat(defaults.effortDefault()).isEqualTo("auto");
        assertThat(defaults.scenarioDefault()).isEqualTo("triage");
        assertThat(defaults.effortChoices()).containsExactly("auto", "quick", "deep");
        // 票 55：preconsultation 场景登记入共享场景清单（预问诊只经草稿标识获得，见 online-consultation 契约）
        assertThat(defaults.scenarios()).containsExactly("triage", "interpretation", "preconsultation");
        assertThat(defaults.longitudeMin()).isEqualTo(-180.0);
        assertThat(defaults.longitudeMax()).isEqualTo(180.0);
        assertThat(defaults.latitudeMin()).isEqualTo(-90.0);
        assertThat(defaults.latitudeMax()).isEqualTo(90.0);
    }

    @Test
    void realtimeEnvelopeAndRoundStatusesAreLoaded() {
        assertThat(contracts.chatRealtime().websocketPath()).isEqualTo("/api/c/chat/ws");
        assertThat(contracts.chatRealtime().envelopeTypes())
                .containsExactly("auth", "authenticated", "chat", "accepted", "event", "error");
        assertThat(contracts.chatRealtime().roundStatuses())
                .containsExactly("ACCEPTED", "RUNNING", "COMPLETED", "FAILED");
        // 命名访问器与契约顺序的映射钉死：消费侧一律经访问器取契约值，不得再硬编码字面量
        Contracts.ChatRealtime realtime = contracts.chatRealtime();
        assertThat(realtime.authEnvelope()).isEqualTo("auth");
        assertThat(realtime.authenticatedEnvelope()).isEqualTo("authenticated");
        assertThat(realtime.chatEnvelope()).isEqualTo("chat");
        assertThat(realtime.acceptedEnvelope()).isEqualTo("accepted");
        assertThat(realtime.eventEnvelope()).isEqualTo("event");
        assertThat(realtime.errorEnvelope()).isEqualTo("error");
        assertThat(realtime.acceptedStatus()).isEqualTo("ACCEPTED");
        assertThat(realtime.runningStatus()).isEqualTo("RUNNING");
        assertThat(realtime.completedStatus()).isEqualTo("COMPLETED");
        assertThat(realtime.failedStatus()).isEqualTo("FAILED");
        assertThat(realtime.thinkingEvent()).isEqualTo("thinking");
        // 票 51/票 50/票 55/票 80：chat 信封可选字段（药品说明书流 / 科室号源失败重试 / 预问诊草稿标识 / 处方选择卡点选回传）
        assertThat(realtime.chatOptionalFields())
                .containsExactly(
                        "medication_name",
                        "retry_standard_department_id",
                        "preconsultation_draft_id",
                        "prescription_id");
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
        // 标准科室工具、两类卡片与点选直查常量来自契约单一事实源
        Contracts.GuidedRegistration guided = contracts.guidedRegistration();
        assertThat(guided.cardEvent()).isEqualTo("department_slots");
        assertThat(guided.cardStatuses()).containsExactly("ok", "failed");
        assertThat(guided.retryRequestField()).isEqualTo("retry_standard_department_id");
        assertThat(guided.summaryTemplates().keySet())
                .containsExactlyInAnyOrder("ok", "empty", "failed", "recommendation");
        assertThat(guided.summaryTemplates().get("ok")).contains("{department}");
        // 票 60：推荐理由子句由 server-py 拼接到 ok 摘要末尾，占位符钉死
        assertThat(guided.summaryTemplates().get("recommendation"))
                .contains("{doctor_name}")
                .contains("{doctor_title}")
                .contains("{doctor_specialty}");
        assertThat(guided.timeSlotLabels()).containsEntry("AM", "上午").containsEntry("PM", "下午");
        assertThat(guided.retryUserText()).isEqualTo("重新查询号源");
        // 候选科室工具的卡事件、候选上限与点选直查文案模板钉死
        assertThat(guided.optionsCardEvent()).isEqualTo("department_options");
        assertThat(guided.optionsMaxCandidates()).isEqualTo(3);
        assertThat(guided.optionsSelectUserText()).contains("{department}");
        assertThat(contracts.sseEvents().cardEvents()).contains(guided.optionsCardEvent());
        assertThat(contracts.sseEvents().messageKinds()).contains(guided.optionsCardEvent());
        // 卡事件名必须与 sse-events 的 card_events/message_kinds 同源一致
        assertThat(contracts.sseEvents().cardEvents()).contains(guided.cardEvent());
        assertThat(contracts.sseEvents().messageKinds()).contains(guided.cardEvent());
        // 重试字段必须与 chat-realtime 的 chat_optional_fields 一致
        assertThat(contracts.chatRealtime().chatOptionalFields()).contains(guided.retryRequestField());
    }

    @Test
    void onlineConsultationContractIsLoaded() {
        // 票 55（Spec 0003）：在线问诊状态机/进度/方式/发送者/超时/文案全部来自契约单一事实源
        Contracts.OnlineConsultation consultation = contracts.onlineConsultation();
        assertThat(consultation.scenario()).isEqualTo("preconsultation");
        assertThat(consultation.draftStatuses())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "collecting", "COLLECTING",
                        "pending_confirm", "PENDING_CONFIRM",
                        "submitted", "SUBMITTED",
                        "abandoned", "ABANDONED"));
        assertThat(consultation.draftStatusLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        consultation.draftStatuses().values());
        assertThat(consultation.statuses())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "waiting_doctor", "WAITING_DOCTOR",
                        "in_progress", "IN_PROGRESS",
                        "completed", "COMPLETED",
                        "cancelled", "CANCELLED",
                        "expired", "EXPIRED"));
        assertThat(consultation.statusLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(consultation.statuses().values());
        // 单一进行中约束：活跃状态集与数据库部分唯一索引的 WHERE 子句一致（ConsistencyTest 另钉 schema）
        assertThat(consultation.activeStatuses()).containsExactly("WAITING_DOCTOR", "IN_PROGRESS");
        assertThat(consultation.decisions())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "accept", "ACCEPT",
                        "cancel", "CANCEL",
                        "complete", "COMPLETE",
                        "resubmit", "RESUBMIT",
                        "abandon", "ABANDON",
                        "end", "END"));
        // C 端固定五步进度：后三步键与问诊状态值同源，终态分支（CANCELLED/EXPIRED）不占步
        assertThat(consultation.progressSteps().stream().map(Contracts.OnlineConsultation.ProgressStep::key))
                .containsExactly("PRECONSULTATION", "SUMMARY_CONFIRMED", "WAITING_DOCTOR", "IN_PROGRESS", "COMPLETED");
        assertThat(consultation.isProgressStatus("WAITING_DOCTOR")).isTrue();
        assertThat(consultation.isProgressStatus("IN_PROGRESS")).isTrue();
        assertThat(consultation.isProgressStatus("COMPLETED")).isTrue();
        assertThat(consultation.isProgressStatus("CANCELLED")).isFalse();
        assertThat(consultation.isProgressStatus("EXPIRED")).isFalse();
        assertThat(consultation.consultMethods())
                .containsExactlyInAnyOrderEntriesOf(Map.of("text", "TEXT", "video", "VIDEO"));
        assertThat(consultation.consultMethodLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        consultation.consultMethods().values());
        assertThat(consultation.isKnownConsultMethod("TEXT")).isTrue();
        assertThat(consultation.isKnownConsultMethod("VIDEO")).isTrue();
        assertThat(consultation.isKnownConsultMethod("AUDIO")).isFalse();
        assertThat(consultation.senderTypes())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("patient", "PATIENT", "doctor", "DOCTOR", "system", "SYSTEM"));
        assertThat(consultation.acceptTimeoutSeconds()).isEqualTo(600);
        // 票 86：固定时长窗自医生接受起计时，到期惰性收敛 EXPIRED（与接诊超时同构）
        assertThat(consultation.consultationDurationSeconds()).isEqualTo(1800);
        assertThat(consultation.summaryFields())
                .containsExactly("chief_complaint", "present_illness", "allergy_history");
        assertThat(consultation.summaryFieldLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(consultation.summaryFields());
        assertThat(consultation.summaryEventField()).isEqualTo("preconsultation_summary");
        // 票 60：随访关怀段钉死——COMPLETED 同事务 eager 生成，visible_at 延迟 delayDays 天可见
        Contracts.OnlineConsultation.FollowUp followUp = consultation.followUp();
        assertThat(followUp.messageType()).isEqualTo("ONLINE_CONSULTATION_FOLLOW_UP");
        assertThat(followUp.title()).isEqualTo("随访关怀");
        assertThat(followUp.content()).isNotBlank();
        assertThat(followUp.delayDays()).isEqualTo(3);
        // 预问诊场景值必须同步登记在 chat-defaults scenarios 与 knowledge 默认映射
        assertThat(contracts.chatDefaults().scenarios()).contains(consultation.scenario());
        assertThat(contracts.knowledge().defaultByScenario()).containsKey(consultation.scenario());
        // 全部用户文案键钉死：状态机出口与系统消息不得私写文案
        assertThat(consultation.texts().keySet())
                .containsExactlyInAnyOrder(
                        "waiting_matching",
                        "expired_hint",
                        "cancelled_hint",
                        "resubmit_hint",
                        "doctor_accepted",
                        "video_started",
                        "consult_completed",
                        "profile_required",
                        "department_unresolved",
                        "summary_required",
                        "scenario_requires_draft",
                        "accept_conflict",
                        "not_waiting",
                        "not_in_progress",
                        "text_started",
                        "method_already_set",
                        "method_required",
                        "draft_abandoned",
                        "patient_ended",
                        "duration_expired");
    }

    @Test
    void healthObservationsContractIsLoaded() {
        // 票 61（ADR-0031）：九项白名单指标、血压组合项拆分、来源/核验/沉淀状态全部来自契约单一事实源
        Contracts.HealthObservations observations = contracts.healthObservations();
        assertThat(observations.metricCodes())
                .containsExactly(
                        "HEIGHT",
                        "WEIGHT",
                        "BMI",
                        "SYSTOLIC_BP",
                        "DIASTOLIC_BP",
                        "FASTING_GLUCOSE",
                        "TOTAL_CHOLESTEROL",
                        "ABO_BLOOD_TYPE",
                        "RH_D_BLOOD_TYPE");
        assertThat(observations.numericValueType()).isEqualTo("NUMERIC");
        assertThat(observations.categoricalValueType()).isEqualTo("CATEGORICAL");
        Contracts.HealthObservations.Metric height = observations.metrics().get("HEIGHT");
        assertThat(height.nameZh()).isEqualTo("身高");
        assertThat(height.valueType()).isEqualTo("NUMERIC");
        assertThat(height.canonicalUnit()).isEqualTo("cm");
        assertThat(height.aliases()).containsExactly("身高");
        assertThat(height.unitAliases()).containsEntry("厘米", "cm");
        assertThat(height.allowUnitMissing()).isFalse();
        // BMI 只允许提取报告原值，空单位按规范单位处理
        Contracts.HealthObservations.Metric bmi = observations.metrics().get("BMI");
        assertThat(bmi.aliases()).contains("BMI", "体质指数", "身体质量指数", "体重指数");
        assertThat(bmi.allowUnitMissing()).isTrue();
        assertThat(bmi.unitAliases()).containsEntry("kg/m2", "kg/m²");
        // 分类指标（血型）：分类值与别名归一
        Contracts.HealthObservations.Metric abo = observations.metrics().get("ABO_BLOOD_TYPE");
        assertThat(abo.valueType()).isEqualTo("CATEGORICAL");
        assertThat(abo.categories()).containsExactly("A", "B", "AB", "O");
        assertThat(abo.categoryAliases()).containsEntry("A型", "A").containsEntry("O 型", "O");
        assertThat(abo.categoryDisplayZh()).containsEntry("A", "A 型");
        Contracts.HealthObservations.Metric rh = observations.metrics().get("RH_D_BLOOD_TYPE");
        assertThat(rh.categories()).containsExactly("POSITIVE", "NEGATIVE");
        assertThat(rh.categoryAliases()).containsEntry("阳性", "POSITIVE").containsEntry("-", "NEGATIVE");
        // 血压组合项拆分规则
        Contracts.HealthObservations.BloodPressurePair pair = observations.bloodPressurePair();
        assertThat(pair.aliases()).containsExactly("血压");
        assertThat(pair.valuePattern()).isEqualTo("^(\\d{2,3})\\s*/\\s*(\\d{2,3})$");
        assertThat(pair.systolicCode()).isEqualTo("SYSTOLIC_BP");
        assertThat(pair.diastolicCode()).isEqualTo("DIASTOLIC_BP");
        assertThat(pair.canonicalUnit()).isEqualTo("mmHg");
        assertThat(pair.allowUnitMissing()).isTrue();
        // 来源、核验状态、患者决定与沉淀状态枚举
        assertThat(observations.sourceTypes())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("report_ai", "REPORT_AI", "user_correction", "USER_CORRECTION"));
        assertThat(observations.reportAiSource()).isEqualTo("REPORT_AI");
        assertThat(observations.userCorrectionSource()).isEqualTo("USER_CORRECTION");
        assertThat(observations.sourceDisplayZh()).containsEntry("REPORT_AI", "报告 AI 提取");
        assertThat(observations.verificationStatuses())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "unverified", "UNVERIFIED",
                        "user_confirmed", "USER_CONFIRMED",
                        "rejected", "REJECTED",
                        "superseded", "SUPERSEDED"));
        assertThat(observations.unverifiedStatus()).isEqualTo("UNVERIFIED");
        assertThat(observations.userConfirmedStatus()).isEqualTo("USER_CONFIRMED");
        assertThat(observations.rejectedStatus()).isEqualTo("REJECTED");
        assertThat(observations.supersededStatus()).isEqualTo("SUPERSEDED");
        assertThat(observations.verificationDisplayZh()).containsEntry("UNVERIFIED", "报告提取 · 待核验");
        assertThat(observations.patientDecisions())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("confirm", "CONFIRM", "correct", "CORRECT", "reject", "REJECT"));
        assertThat(observations.itemStates())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "deposited_unverified", "DEPOSITED_UNVERIFIED",
                        "deposited_confirmed", "DEPOSITED_CONFIRMED",
                        "deposited_rejected", "DEPOSITED_REJECTED",
                        "duplicate_slot", "DUPLICATE_SLOT",
                        "conflict_skipped", "CONFLICT_SKIPPED",
                        "no_date", "NO_DATE",
                        "unmapped", "UNMAPPED"));
        assertThat(observations.itemStateDisplayZh()).containsEntry("NO_DATE", "报告缺少明确检查日期，未沉淀");
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
                .containsEntry("interpretation", "none")
                // 票 55：预问诊场景知识源默认 rag（与 chat-defaults scenarios 同步登记）
                .containsEntry("preconsultation", "rag");
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
        // 票 88（ADR-0035）：九值状态机——配送/自取两条前向路径 + 取消/过期两个终止态；DONE 已删除
        assertThat(flow.statuses())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "unpaid", "UNPAID",
                        "paid", "PAID",
                        "dispensing", "DISPENSING",
                        "shipped", "SHIPPED",
                        "delivered", "DELIVERED",
                        "ready_for_pickup", "READY_FOR_PICKUP",
                        "picked_up", "PICKED_UP",
                        "cancelled", "CANCELLED",
                        "expired", "EXPIRED"));
        assertThat(flow.statusLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(flow.statuses().values());
        assertThat(flow.statusLabels()).containsEntry("UNPAID", "待支付").containsEntry("PICKED_UP", "已取药");
        // C 端待支付动作 + B 端模拟履约推进动作；旧 complete 已删除
        assertThat(flow.decisions())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "pay", "PAY",
                        "cancel", "CANCEL",
                        "dispense", "DISPENSE",
                        "ship", "SHIP",
                        "deliver", "DELIVER",
                        "ready", "READY",
                        "pickup", "PICKUP"));
        assertThat(flow.messages()).containsEntry("stock_insufficient", "药品库存不足，下单失败");
        // 票 76（ADR-0032）：订单来源区分处方药/OTC。
        assertThat(flow.sources()).containsEntry("prescription", "PRESCRIPTION").containsEntry("otc", "OTC");
        assertThat(flow.sourceLabels()).containsEntry("PRESCRIPTION", "处方药").containsEntry("OTC", "非处方药");
        // 票 88：取药方式（院区自取/配送到家）、10 分钟待支付与虚构承运方
        assertThat(flow.pickupMethods())
                .containsExactlyInAnyOrderEntriesOf(Map.of("pickup", "PICKUP", "delivery", "DELIVERY"));
        assertThat(flow.pickupMethodLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(flow.pickupMethods().values());
        assertThat(flow.paymentTimeoutSeconds()).isEqualTo(600);
        assertThat(flow.simulatedCarrierName()).isEqualTo("智愈模拟配送");
    }

    @Test
    void staffRolesAreLoaded() {
        // 票 88（ADR-0035）：B 端角色单一事实源——小写取值沿用现有惯例，新增全局药师
        Contracts.StaffRoles staffRoles = contracts.staffRoles();
        assertThat(staffRoles.roles())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("admin", "admin", "doctor", "doctor", "pharmacist", "pharmacist"));
        assertThat(staffRoles.roleLabels())
                .containsExactlyInAnyOrderEntriesOf(Map.of("admin", "管理员", "doctor", "医生", "pharmacist", "药师"));
        assertThat(staffRoles.roleLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(staffRoles.roles().values());
        // StaffUser 角色常量是 Java 侧权限判断入口（拦截器/service），必须与契约取值同源。
        assertThat(com.zhiyu.health.entity.common.StaffUser.ROLE_ADMIN)
                .isEqualTo(staffRoles.roles().get("admin"));
        assertThat(com.zhiyu.health.entity.common.StaffUser.ROLE_DOCTOR)
                .isEqualTo(staffRoles.roles().get("doctor"));
        assertThat(com.zhiyu.health.entity.common.StaffUser.ROLE_PHARMACIST)
                .isEqualTo(staffRoles.roles().get("pharmacist"));
    }

    @Test
    void contractsDirCanBeOverridden() {
        // 解析顺序：系统属性 > 环境变量 > 默认 ../contracts；此处只断言默认值可用。
        assertThat(contracts.sseEvents().toolToEvent())
                .isEqualTo(Map.of(
                        "recommend_doctors", "doctor_recommendations",
                        "get_doctor_slots", "doctor_slots",
                        "create_appointment", "appointment",
                        "get_appointment", "appointments",
                        "get_standard_department_slots", "department_slots",
                        "suggest_standard_departments", "department_options",
                        "search_medications", "medications",
                        "list_approved_prescriptions", "prescriptions",
                        "prepare_drug_order", "drug_order_prepare"));
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
        // 票 87：演示时段覆盖的 Redis 键单一事实源
        assertThat(demo.timeSlotWindowsRedisKey()).isEqualTo("demo:time_slot_windows");
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
    void appointmentFlowContractIsLoaded() {
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        assertThat(flow.statuses())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "pending_payment", "PENDING_PAYMENT",
                        "booked", "BOOKED",
                        "in_progress", "IN_PROGRESS",
                        "cancelled", "CANCELLED",
                        "visited", "VISITED"));
        assertThat(flow.statusLabels())
                .containsEntry("PENDING_PAYMENT", "待支付")
                .containsEntry("BOOKED", "待就诊")
                .containsEntry("IN_PROGRESS", "就诊中")
                .containsEntry("CANCELLED", "已取消")
                .containsEntry("VISITED", "已接诊");
        // 支付门控（票 81）：待支付 -> 待就诊，支付完成才对接诊台可见。
        assertThat(flow.transitions().get("pay").from()).containsExactly("PENDING_PAYMENT");
        assertThat(flow.transitions().get("pay").to()).isEqualTo("BOOKED");
        assertThat(flow.transitions().get("call").from()).containsExactly("BOOKED");
        assertThat(flow.transitions().get("call").to()).isEqualTo("IN_PROGRESS");
        // 票 87：接诊完成只能从就诊中推进，废弃 BOOKED -> VISITED 直通兜底。
        assertThat(flow.transitions().get("complete").from()).containsExactly("IN_PROGRESS");
        assertThat(flow.transitions().get("complete").to()).isEqualTo("VISITED");
        // 取消放宽：待支付与待就诊均可取消，均释放号源。
        assertThat(flow.transitions().get("cancel").from()).containsExactly("PENDING_PAYMENT", "BOOKED");
        assertThat(flow.transitions().get("cancel").to()).isEqualTo("CANCELLED");
        // 支付截止默认 60 秒（演示便于观察超时收敛）；接诊台可见白名单排除待支付。
        assertThat(flow.paymentTimeoutSeconds()).isEqualTo(60);
        assertThat(flow.receptionVisibleStatuses()).containsExactly("BOOKED", "IN_PROGRESS", "VISITED");
        Contracts.AppointmentFlow.CalledNotice notice = flow.calledNotice();
        assertThat(notice.messageType()).isEqualTo("appointment_called");
        assertThat(notice.title()).isEqualTo("请到诊室就诊");
        assertThat(notice.greeting()).contains("就诊序号");
        assertThat(notice.contentSchema())
                .containsExactly("greeting", "room", "sequence_number", "schedule_date", "time_slot");
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

    @Test
    void scheduleRequestFlowContractIsLoaded() {
        // 排班申请审核流：状态机/决定/操作类型/时段窗口/可排天数上限/号源上限来自契约单一事实源
        Contracts.ScheduleRequestFlow flow = contracts.scheduleRequestFlow();
        assertThat(flow.statuses())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("pending", "PENDING", "approved", "APPROVED", "rejected", "REJECTED"));
        assertThat(flow.statusLabels())
                .containsEntry("PENDING", "待审核")
                .containsEntry("APPROVED", "已通过")
                .containsEntry("REJECTED", "已驳回");
        assertThat(flow.decisions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("approve", "APPROVE", "reject", "REJECT"));
        // 操作类型：CREATE 新增 / MODIFY 调整号源 / DISABLE 停诊 / ENABLE 恢复出诊
        assertThat(flow.actions())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("create", "CREATE", "modify", "MODIFY", "disable", "DISABLE", "enable", "ENABLE"));
        assertThat(flow.actionLabels())
                .containsEntry("CREATE", "新增排班")
                .containsEntry("MODIFY", "调整号源")
                .containsEntry("DISABLE", "停诊")
                .containsEntry("ENABLE", "恢复出诊");
        // 时段去掉晚上，只保留上午/下午
        assertThat(flow.timeSlots())
                .hasSize(2)
                .containsEntry("morning", "上午")
                .containsEntry("afternoon", "下午")
                .doesNotContainKey("evening");
        // 时段窗口：上午 09:00-11:30，下午 14:00-18:00（挂号截止校验的单一事实源）
        Contracts.ScheduleRequestFlow.TimeSlotWindow morning =
                flow.timeSlotWindows().get("上午");
        assertThat(morning.start()).isEqualTo("09:00");
        assertThat(morning.end()).isEqualTo("11:30");
        Contracts.ScheduleRequestFlow.TimeSlotWindow afternoon =
                flow.timeSlotWindows().get("下午");
        assertThat(afternoon.start()).isEqualTo("14:00");
        assertThat(afternoon.end()).isEqualTo("18:00");
        // 可排班天数上限是双栈共享约束，server-java 校验与 admin 前端日期范围均读此值
        assertThat(flow.maxDaysAhead()).isEqualTo(14);
        assertThat(flow.maxTotalSlots()).isEqualTo(50);
        assertThat(flow.reviewReasonMaxLength()).isEqualTo(500);
    }
}
