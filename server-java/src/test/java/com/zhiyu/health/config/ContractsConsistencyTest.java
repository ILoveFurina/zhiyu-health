package com.zhiyu.health.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiyu.health.entity.Message;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
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
                        "report_context",
                        "skin_analysis",
                        "image",
                        "diet_analysis",
                        "tongue_analysis",
                        "department_slots",
                        "department_options");
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
        assertThat(Message.KIND_SKIN_ANALYSIS).isEqualTo(events.messageKinds().get(9));
        assertThat(Message.KIND_IMAGE).isEqualTo(events.messageKinds().get(10));
        assertThat(Message.KIND_DIET_ANALYSIS).isEqualTo(events.messageKinds().get(11));
        assertThat(Message.KIND_TONGUE_ANALYSIS).isEqualTo(events.messageKinds().get(12));
    }

    @Test
    void aiCardKindPredicateCoversExactlyContractList() {
        for (String kind : contracts.sseEvents().aiCardKinds()) {
            assertThat(Message.isAiCardKind(kind)).isTrue();
        }
        assertThat(Message.isAiCardKind(Message.KIND_TEXT)).isFalse();
        assertThat(Message.isAiCardKind(Message.KIND_REPORT_UPLOAD)).isFalse();
        assertThat(Message.isAiCardKind(Message.KIND_REPORT_CONTEXT)).isFalse();
        // image 是用户上传的原图路径消息，不属于 AI 产出卡片（ADR-0023）
        assertThat(Message.isAiCardKind(Message.KIND_IMAGE)).isFalse();
        // skin_analysis 是 AI 产出的结构化卡片，属于 ai_card_kinds
        assertThat(Message.isAiCardKind(Message.KIND_SKIN_ANALYSIS)).isTrue();
        // diet_analysis 是 AI 产出的结构化卡片，属于 ai_card_kinds（票 16）
        assertThat(Message.isAiCardKind(Message.KIND_DIET_ANALYSIS)).isTrue();
        // tongue_analysis 是 AI 产出的中医辨证卡片，属于 ai_card_kinds（票 17）
        assertThat(Message.isAiCardKind(Message.KIND_TONGUE_ANALYSIS)).isTrue();
        // 票 51（ADR-0028）：medication_info/medication_safety 双卡片出口已删除，不再是任何 kind
        assertThat(Message.isAiCardKind("medication_info")).isFalse();
        assertThat(Message.isAiCardKind("medication_safety")).isFalse();
        // red_flag 是规则引擎产物，不属于 AI 卡片
        assertThat(Message.isAiCardKind("red_flag")).isFalse();
        // 票 50：department_slots 是编排代码确定性产出的 AI 卡片
        assertThat(Message.isAiCardKind("department_slots")).isTrue();
        // 票 65：department_options 科室选择卡同样由编排代码产出，属于 ai_card_kinds
        assertThat(Message.isAiCardKind("department_options")).isTrue();
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
        assertThat(events.isTraceEvent(contracts.chatRealtime().thinkingEvent()))
                .isFalse();
        assertThat(events.streamEvents())
                .doesNotContain(contracts.chatRealtime().thinkingEvent());

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
    void llmContextExclusionIsAiCardKindsPlusReportUploadAndImage() {
        // ConversationService.recentContext 的排除集 = 契约 ai_card_kinds + report_upload + image。
        // 卡片 JSON 与图片路径用于历史渲染，不是自然语言，避免重复塞回 LLM 上下文（ADR-0023）。
        Set<String> excluded = new HashSet<>(contracts.sseEvents().aiCardKinds());
        excluded.add(Message.KIND_REPORT_UPLOAD);
        excluded.add(Message.KIND_IMAGE);
        assertThat(excluded)
                .containsExactlyInAnyOrder(
                        "doctor_recommendations",
                        "doctor_slots",
                        "hospital_recommendations",
                        "appointment",
                        "appointments",
                        "report_interpretation",
                        "skin_analysis",
                        "diet_analysis",
                        "tongue_analysis",
                        "department_slots",
                        "department_options",
                        "report_upload",
                        "image");
    }

    @Test
    void messageKindsAreCoveredBySchemaCheckConstraint() throws Exception {
        // 票 50：messages.kind 的 ck_messages_kind CHECK 必须覆盖全部契约 kind + red_flag，
        // 漏列会使卡片落库在 DB 层被拒并掐断 SSE 中继（票 33 同类故障）
        String schema = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("schema.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        int start = schema.indexOf("ck_messages_kind");
        assertThat(start).as("schema.sql 必须存在 ck_messages_kind 约束").isGreaterThanOrEqualTo(0);
        String constraintRegion = schema.substring(start);
        for (String kind : contracts.sseEvents().messageKinds()) {
            assertThat(constraintRegion)
                    .as("ck_messages_kind 必须覆盖契约 kind %s", kind)
                    .contains("'" + kind + "'");
        }
        assertThat(constraintRegion).contains("'" + contracts.sseEvents().redFlagEvent() + "'");
    }

    @Test
    void onlineConsultationEnumsAreCoveredBySchemaCheckConstraints() throws Exception {
        // 票 55：新表 CHECK 必须覆盖契约全部枚举值，漏列会在 DB 层拒写并掐断问诊闭环
        // （与 ck_messages_kind 同一纪律，取值清单一致性由本测试钉死）
        String schema = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("schema.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        Contracts.OnlineConsultation consultation = contracts.onlineConsultation();
        for (String status : consultation.draftStatuses().values()) {
            assertThat(schema)
                    .as("preconsultation_drafts CHECK 必须覆盖草稿状态 %s", status)
                    .contains("'" + status + "'");
        }
        for (String status : consultation.statuses().values()) {
            assertThat(schema)
                    .as("online_consultations CHECK 必须覆盖问诊状态 %s", status)
                    .contains("'" + status + "'");
        }
        for (String method : consultation.consultMethods().values()) {
            assertThat(schema)
                    .as("online_consultations CHECK 必须覆盖接诊方式 %s", method)
                    .contains("'" + method + "'");
        }
        for (String sender : consultation.senderTypes().values()) {
            assertThat(schema)
                    .as("online_consultation_messages CHECK 必须覆盖发送者类型 %s", sender)
                    .contains("'" + sender + "'");
        }
        for (String kind : consultation.messageKinds()) {
            assertThat(schema)
                    .as("online_consultation_messages CHECK 必须覆盖消息类型 %s", kind)
                    .contains("'" + kind + "'");
        }
        // 单一进行中约束：部分唯一索引必须存在，且 WHERE 子句覆盖契约 active_statuses 全部取值
        assertThat(schema).contains("uq_online_consultations_active_profile");
        int indexStart = schema.indexOf("uq_online_consultations_active_profile");
        String indexRegion = schema.substring(indexStart, schema.indexOf(";", indexStart));
        for (String active : consultation.activeStatuses()) {
            assertThat(indexRegion).as("活跃问诊部分唯一索引必须覆盖状态 %s", active).contains("'" + active + "'");
        }
        assertThat(schema).contains("uq_preconsultation_drafts_active");
    }

    @Test
    void healthObservationEnumsAreCoveredBySchemaCheckConstraints() throws Exception {
        // 票 61（ADR-0031）：health_observations 的 CHECK 必须覆盖契约全部枚举值，
        // 漏列会在 DB 层拒写并掐断报告解读成功链路（与 ck_messages_kind 同一纪律）
        String schema = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("schema.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        Contracts.HealthObservations observations = contracts.healthObservations();
        Matcher metricMatcher =
                Pattern.compile("ck_health_observations_metric\\s").matcher(schema);
        assertThat(metricMatcher.find())
                .as("schema.sql 必须存在 ck_health_observations_metric 约束")
                .isTrue();
        int metricStart = metricMatcher.start();
        String metricRegion = schema.substring(metricStart, schema.indexOf("),", metricStart));
        for (String metricCode : observations.metricCodes()) {
            assertThat(metricRegion).as("指标 CHECK 必须覆盖 %s", metricCode).contains("'" + metricCode + "'");
        }
        int categoryStart = schema.indexOf("ck_health_observations_category");
        assertThat(categoryStart)
                .as("schema.sql 必须存在 ck_health_observations_category 约束")
                .isGreaterThanOrEqualTo(0);
        String categoryRegion = schema.substring(categoryStart, schema.indexOf(")", categoryStart));
        for (Contracts.HealthObservations.Metric metric : observations.metrics().values()) {
            for (String category : metric.categories()) {
                assertThat(categoryRegion).as("分类值 CHECK 必须覆盖 %s", category).contains("'" + category + "'");
            }
        }
        int sourceStart = schema.indexOf("ck_health_observations_source");
        String sourceRegion = schema.substring(sourceStart, schema.indexOf(")", sourceStart));
        for (String source : observations.sourceTypes().values()) {
            assertThat(sourceRegion).as("来源 CHECK 必须覆盖 %s", source).contains("'" + source + "'");
        }
        int statusStart = schema.indexOf("ck_health_observations_status");
        String statusRegion = schema.substring(statusStart, schema.indexOf(")", statusStart));
        for (String status : observations.verificationStatuses().values()) {
            assertThat(statusRegion).as("核验状态 CHECK 必须覆盖 %s", status).contains("'" + status + "'");
        }
        // 两个部分唯一索引：同报告映射幂等 + 每日当前槽位唯一（沉淀并发收敛的 DB 兜底）
        assertThat(schema).contains("uq_health_observations_report_metric");
        int reportIndexStart = schema.indexOf("uq_health_observations_report_metric");
        String reportIndexRegion = schema.substring(reportIndexStart, schema.indexOf(";", reportIndexStart));
        assertThat(reportIndexRegion)
                .contains("'" + observations.reportAiSource() + "'")
                .contains("report_interpretation_id")
                .contains("metric_code");
        assertThat(schema).contains("uq_health_observations_current_slot");
        int slotIndexStart = schema.indexOf("uq_health_observations_current_slot");
        String slotIndexRegion = schema.substring(slotIndexStart, schema.indexOf(";", slotIndexStart));
        assertThat(slotIndexRegion)
                .contains("health_profile_id")
                .contains("observed_on")
                .contains("WHERE current = TRUE");
    }

    @Test
    void uploadTypeAccessorsMatchContract() {
        Contracts.UploadLimits limits = contracts.uploadLimits();
        assertThat(limits.pdfType()).isEqualTo("application/pdf");
        assertThat(limits.imageTypes()).containsExactly("image/jpeg", "image/png", "image/webp");
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
        // 票 60：审核结果站内消息类型与 approved/rejected 文案钉死（type 与消息键同源取值）
        assertThat(flow.messageTypes()).containsEntry("prescription_review_result", "PRESCRIPTION_REVIEW_RESULT");
        assertThat(flow.messages().keySet()).containsExactlyInAnyOrder("approved", "rejected");
        assertThat(flow.messages().get("approved").title()).isEqualTo("处方审核通过");
        assertThat(flow.messages().get("rejected").title()).isEqualTo("处方审核未通过");
        // 票 56：处方来源二态（线下挂号/在线问诊），仅是外键派生展示值，数据库不落 source_type 列
        assertThat(flow.sourceTypes())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("appointment", "APPOINTMENT", "online_consultation", "ONLINE_CONSULTATION"));
        assertThat(flow.sourceTypeLabels().keySet())
                .containsExactlyInAnyOrderElementsOf(flow.sourceTypes().values());
    }

    @Test
    void inAppMessagesEventSourcesMatchSchema() throws Exception {
        // 票 60：in_app_messages 三类事件来源外键各配 UNIQUE（重投幂等），visible_at 支撑随访延迟可见；
        // 与 ck_messages_kind 同一纪律——约束漂移会在 DB 层放过重复消息或拒写随访
        String schema = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("schema.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(schema)
                .contains("related_prescription_id")
                .contains("related_online_consultation_id")
                .contains("visible_at TIMESTAMPTZ NOT NULL DEFAULT now()")
                .contains("uq_in_app_messages_appointment_type")
                .contains("uq_in_app_messages_prescription_type")
                .contains("uq_in_app_messages_consultation_type")
                .contains("fk_in_app_messages_online_consultation");
        // 处方审核结果与随访消息类型必须装得下 type VARCHAR(40) 列宽
        assertThat(contracts
                        .prescriptionFlow()
                        .messageTypes()
                        .get("prescription_review_result")
                        .length())
                .isLessThanOrEqualTo(40);
        assertThat(contracts.onlineConsultation().followUp().messageType().length())
                .isLessThanOrEqualTo(40);
    }

    @Test
    void appointmentStatusesAndCalledNoticeMatchSchema() throws Exception {
        String schema = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("schema.sql"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("CONSTRAINT ck_appointments_status CHECK \\(status IN \\(([^)]*)\\)\\)")
                .matcher(schema);
        assertThat(matcher.find())
                .as("schema.sql 必须存在 ck_appointments_status 约束")
                .isTrue();
        String allowed = matcher.group(1);
        for (String status : contracts.appointmentFlow().statuses().values()) {
            assertThat(allowed).as("挂号状态契约值 %s 必须被数据库 CHECK 接受", status).contains("'" + status + "'");
        }
        assertThat(contracts.appointmentFlow().calledNotice().messageType().length())
                .isLessThanOrEqualTo(40);
        assertThat(schema).contains("uq_in_app_messages_appointment_type");
    }

    @Test
    void onlineConsultationTimelineTypeIsLoaded() {
        // 票 56：COMPLETED 在线问诊进入健康档案时间线，条目类型与 med-checkin 同一契约约定
        assertThat(contracts.onlineConsultation().timelineTypes())
                .containsEntry("online_consultation", "ONLINE_CONSULTATION");
    }

    @Test
    void healthTimelineSqlLiteralsMatchContracts() throws Exception {
        // 票 56：HealthProfileMapper.selectTimeline 是静态 SQL，类型字面量必须与契约值一致，
        // 漂移会让 C 端时间线出现契约外类型（ mapper 无法注入 Contracts，只能在此钉死）。
        String sql = String.join(
                "\n",
                com.zhiyu.health.mapper.HealthProfileMapper.class
                        .getMethod("selectTimeline", long.class, long.class)
                        .getAnnotation(org.apache.ibatis.annotations.Select.class)
                        .value());
        String onlineType = contracts.onlineConsultation().timelineTypes().get("online_consultation");
        assertThat(sql).as("时间线 SQL 必须含在线问诊条目类型字面量 %s", onlineType).contains("'" + onlineType + "'");
        String medCheckinType = contracts.medCheckinFlow().timelineTypes().get("med_checkin");
        assertThat(sql).as("时间线 SQL 必须含服药打卡条目类型字面量 %s", medCheckinType).contains("'" + medCheckinType + "'");
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
        // 票 45 骨架 + 票 58（ADR-0029）：asr_enabled 已点亮为 true（Fake 阶段），
        // tts_enabled 保持 false；格式字段仍留 null，开通后只填值不改结构
        Contracts.Voice voice = contracts.voice();
        assertThat(voice.asrEnabled()).isTrue();
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
