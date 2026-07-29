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
        assertThat(events.streamEvents()).containsExactly("meta", "token", "message", "done");
        assertThat(events.redFlagEvent()).isEqualTo("red_flag");
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
}
