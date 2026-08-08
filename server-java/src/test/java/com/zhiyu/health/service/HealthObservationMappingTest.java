package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.support.TestContracts;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 报告观测确定性映射：别名/单位/血压拆分/去重/冲突/日期降级的全规则覆盖。 */
class HealthObservationMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Contracts contracts = TestContracts.instance();
    private final HealthObservationMapping mapping = new HealthObservationMapping(contracts);

    @Test
    void mapsNineWhitelistedMetricsWithAliasAndUnitNormalization() throws Exception {
        JsonNode result = read(
                """
                {"sample_or_exam_date":"2026-05-20","items":[
                  {"name":"身高","value":"165","unit":"厘米","reference_range":"无"},
                  {"name":"体重","value":"58.5","unit":"公斤","reference_range":"无"},
                  {"name":"体质指数","value":"21.3","unit":"kg/m2","reference_range":"18.5-24.0"},
                  {"name":"收缩压（高压）","value":"122","unit":"毫米汞柱","reference_range":"90-140"},
                  {"name":"低压","value":"78","unit":"mmhg","reference_range":"60-90"},
                  {"name":"空腹血糖（GLU）","value":"5.3","unit":"mmol/l","reference_range":"3.9-6.1"},
                  {"name":"总胆固醇（TC）","value":"4.5","unit":"MMOL/L","reference_range":"2.8-5.2"},
                  {"name":"血型","value":"A型","unit":null},
                  {"name":"Rh（D）血型","value":"阳性","unit":null}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        assertThat(report.observedOn()).isEqualTo(LocalDate.parse("2026-05-20"));
        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::metricCode)
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
        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::unit)
                .containsExactly("cm", "kg", "kg/m²", "mmHg", "mmHg", "mmol/L", "mmol/L", null, null);
        assertThat(report.candidates().get(1).valueNumeric()).isEqualByComparingTo(new BigDecimal("58.5"));
        // 参考范围只抄录原文，"无" 归一为 null
        assertThat(report.candidates().get(0).referenceRange()).isNull();
        assertThat(report.candidates().get(2).referenceRange()).isEqualTo("18.5-24.0");
        // 分类值归一到契约 categories
        assertThat(report.candidates().get(7).valueCategory()).isEqualTo("A");
        assertThat(report.candidates().get(8).valueCategory()).isEqualTo("POSITIVE");
    }

    @Test
    void skipsUnknownUnitAndUnparsableValueDeterministically() throws Exception {
        JsonNode result = read(
                """
                {"report_date":"2026-05-20","items":[
                  {"name":"身高","value":"165","unit":"尺","reference_range":"无"},
                  {"name":"体重","value":"约58","unit":"kg","reference_range":"无"},
                  {"name":"身高","value":"165","reference_range":"无"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        // 未知单位（身高 allow_unit_missing=false）、非数字值、缺单位：均不产生候选且状态 UNMAPPED
        assertThat(report.candidates()).isEmpty();
        assertThat(report.items())
                .extracting(HealthObservationMapping.ItemOutcome::state)
                .containsOnly(contracts.healthObservations().itemStates().get("unmapped"));
    }

    @Test
    void skipsUnknownBloodCategory() throws Exception {
        JsonNode result = read(
                """
                {"report_date":"2026-05-20","items":[
                  {"name":"血型","value":"熊猫血"},
                  {"name":"Rh血型","value":"Rh（-）"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::metricCode)
                .containsExactly("RH_D_BLOOD_TYPE");
        assertThat(report.candidates().get(0).valueCategory()).isEqualTo("NEGATIVE");
    }

    @Test
    void neverDerivesBmiFromHeightAndWeight() throws Exception {
        JsonNode result = read(
                """
                {"report_date":"2026-05-20","items":[
                  {"name":"身高","value":"165","unit":"cm"},
                  {"name":"体重","value":"58.5","unit":"kg"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        // BMI 只提取报告原值：有身高体重但无 BMI 项时绝不推算
        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::metricCode)
                .containsExactly("HEIGHT", "WEIGHT");
    }

    @Test
    void dedupesIdenticalBloodPressurePairItemsWithinSameReport() throws Exception {
        JsonNode result = read(
                """
                {"sample_or_exam_date":"2026-02-18","items":[
                  {"name":"血压","value":"118/76","unit":"mmHg","reference_range":"90-140/60-90"},
                  {"name":"血压","value":" 118 / 76 ","unit":"毫米汞柱"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        // 两条组合项拆出的候选完全相同（值 + 归一单位）：按指标去重为各一条
        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::metricCode)
                .containsExactly("SYSTOLIC_BP", "DIASTOLIC_BP");
    }

    @Test
    void splitsBloodPressurePairWithMissingUnit() throws Exception {
        JsonNode result = read(
                """
                {"sample_or_exam_date":"2026-02-18","items":[
                  {"name":"血压","value":"118/76","reference_range":"90-140/60-90"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::metricCode)
                .containsExactly("SYSTOLIC_BP", "DIASTOLIC_BP");
        assertThat(report.candidates().get(0).valueNumeric()).isEqualByComparingTo(new BigDecimal("118"));
        assertThat(report.candidates().get(1).valueNumeric()).isEqualByComparingTo(new BigDecimal("76"));
        // 组合项 allow_unit_missing：空单位按规范单位 mmHg 处理
        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::unit)
                .containsOnly("mmHg");
        assertThat(report.candidates().get(0).referenceRange()).isEqualTo("90-140/60-90");
    }

    @Test
    void rejectsMalformedBloodPressureValue() throws Exception {
        JsonNode result = read(
                """
                {"sample_or_exam_date":"2026-02-18","items":[
                  {"name":"血压","value":"118-76","unit":"mmHg"},
                  {"name":"血压","value":"偏高","unit":"mmHg"},
                  {"name":"血压","value":"1180/76","unit":"mmHg"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        // 非法值不匹配 value_pattern：整项不沉淀（非法项不产生候选，也不构成冲突）
        assertThat(report.candidates()).isEmpty();
        assertThat(report.items())
                .extracting(HealthObservationMapping.ItemOutcome::state)
                .containsOnly(contracts.healthObservations().itemStates().get("unmapped"));
    }

    @Test
    void dedupesIdenticalDuplicateCandidatesWithinSameReport() throws Exception {
        JsonNode result = read(
                """
                {"report_date":"2026-05-20","items":[
                  {"name":"空腹血糖","value":"5.3","unit":"mmol/L","reference_range":"3.9-6.1"},
                  {"name":"血糖（空腹）","value":"5.30","unit":"mmol/l","reference_range":"3.9-6.1"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        // 同报告同指标值完全相同（数值去尾零、单位归一后相同）：去重为一条
        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::metricCode)
                .containsExactly("FASTING_GLUCOSE");
        assertThat(report.candidates().get(0).unit()).isEqualTo("mmol/L");
    }

    @Test
    void skipsWholeMetricGroupOnConflictingValues() throws Exception {
        JsonNode result = read(
                """
                {"report_date":"2026-05-20","items":[
                  {"name":"空腹血糖","value":"5.3","unit":"mmol/L"},
                  {"name":"空腹血糖","value":"6.1","unit":"mmol/L"},
                  {"name":"体重","value":"58.5","unit":"kg"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        // 同报告同指标出现不同值：该指标整组不沉淀（禁止取首次/末次），其他指标不受影响
        assertThat(report.candidates())
                .extracting(HealthObservationMapping.Candidate::metricCode)
                .containsExactly("WEIGHT");
        assertThat(report.items().get(0).state())
                .isEqualTo(contracts.healthObservations().itemStates().get("conflict_skipped"));
        assertThat(report.items().get(1).state())
                .isEqualTo(contracts.healthObservations().itemStates().get("conflict_skipped"));
        assertThat(report.items().get(2).state()).isNull();
    }

    @Test
    void marksWholeReportNoDateWhenBothDatesMissing() throws Exception {
        JsonNode result = read(
                """
                {"sample_or_exam_date":null,"items":[
                  {"name":"身高","value":"165","unit":"cm"},
                  {"name":"血压","value":"118/76","unit":"mmHg"},
                  {"name":"丙氨酸氨基转移酶","value":"18","unit":"U/L"}
                ]}
                """);

        HealthObservationMapping.ReportMapping report = mapping.mapReport(result);

        assertThat(report.observedOn()).isNull();
        assertThat(report.candidates()).isEmpty();
        // 白名单项 NO_DATE；非白名单项始终 UNMAPPED
        assertThat(report.items().get(0).state())
                .isEqualTo(contracts.healthObservations().itemStates().get("no_date"));
        assertThat(report.items().get(1).state())
                .isEqualTo(contracts.healthObservations().itemStates().get("no_date"));
        assertThat(report.items().get(2).state())
                .isEqualTo(contracts.healthObservations().itemStates().get("unmapped"));
    }

    @Test
    void prefersSampleDateAndDegradesToReportDate() throws Exception {
        JsonNode both = read(
                """
                {"sample_or_exam_date":"2026-02-18","report_date":"2026-02-20","items":[]}
                """);
        JsonNode reportOnly = read("""
                {"report_date":"2026-02-20","items":[]}
                """);
        JsonNode invalidSample = read(
                """
                {"sample_or_exam_date":"2026-13-40","report_date":"2026-02-20","items":[]}
                """);

        assertThat(mapping.mapReport(both).observedOn()).isEqualTo(LocalDate.parse("2026-02-18"));
        assertThat(mapping.mapReport(reportOnly).observedOn()).isEqualTo(LocalDate.parse("2026-02-20"));
        // 非法采样日确定性降级到报告日
        assertThat(mapping.mapReport(invalidSample).observedOn()).isEqualTo(LocalDate.parse("2026-02-20"));
    }

    @Test
    void toleratesMissingDatesAndEmptyItems() throws Exception {
        // server-py 新增日期字段前的历史形状：字段整体缺失要容忍
        JsonNode legacy = read("{\"summary\":\"旧报告\",\"items\":[]}");
        HealthObservationMapping.ReportMapping report = mapping.mapReport(legacy);
        assertThat(report.observedOn()).isNull();
        assertThat(report.candidates()).isEmpty();

        HealthObservationMapping.ReportMapping empty = mapping.mapReport(null);
        assertThat(empty.observedOn()).isNull();
        assertThat(empty.candidates()).isEmpty();
    }

    private JsonNode read(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
