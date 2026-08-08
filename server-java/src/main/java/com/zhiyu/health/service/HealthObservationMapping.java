package com.zhiyu.health.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyu.health.config.Contracts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 报告观测确定性映射（票 61，ADR-0031）：把 result_json 的白名单项目按契约映射为沉淀候选。
 * 纯逻辑组件，不访问 DB；坏数据一律返回跳过结果，不抛异常、不让整份报告失败。
 * 规则要点：
 * - observed_on：sample_or_exam_date 优先，report_date 降级；两者均缺整份不沉淀（逐项 NO_DATE）；
 * - 别名精确匹配契约；值/单位/分类无法归一的项视为未映射（不产生候选）；
 * - BMI 只提取报告原值，本组件绝不出现身高体重推算；
 * - 同报告同指标：值完全相同的重复候选去重，出现不同值整组跳过（CONFLICT_SKIPPED），禁止取首次/末次。
 */
@Component
@RequiredArgsConstructor
public class HealthObservationMapping {

    // 数值先经确定性格式校验再构造 BigDecimal，坏值只产生跳过结果，不依赖异常做控制流。
    private static final Pattern NUMERIC_VALUE = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final String REFERENCE_NONE = "无";

    private final Contracts contracts;

    /** 整份报告映射：逐项结果 + 收敛后的沉淀候选。result 为 null/缺字段时返回空结果。 */
    public ReportMapping mapReport(JsonNode result) {
        Contracts.HealthObservations contract = contracts.healthObservations();
        LocalDate observedOn = observedOn(result);
        List<ItemOutcome> outcomes = new ArrayList<>();
        JsonNode items = result == null ? null : result.path("items");
        if (items != null && items.isArray()) {
            int index = 0;
            for (JsonNode item : items) {
                outcomes.add(mapItem(contract, item, index++, observedOn));
            }
        }
        return new ReportMapping(observedOn, resolveConflicts(contract, outcomes));
    }

    /** 观测日期：采样/检查日优先，仅报告日降级；非法日期视为缺失（确定性跳过）。 */
    public LocalDate observedOn(JsonNode result) {
        if (result == null) {
            return null;
        }
        LocalDate sampleDate = parseDate(text(result, "sample_or_exam_date"));
        return sampleDate != null ? sampleDate : parseDate(text(result, "report_date"));
    }

    private List<ItemOutcome> resolveConflicts(Contracts.HealthObservations contract, List<ItemOutcome> outcomes) {
        // 按指标分组（保持契约声明顺序无关，LinkedHashMap 保持出现顺序即可，组内全同去重/全异跳过）。
        Map<String, List<CandidateWithItem>> byMetric = new LinkedHashMap<>();
        for (ItemOutcome outcome : outcomes) {
            for (Candidate candidate : outcome.candidates()) {
                byMetric.computeIfAbsent(candidate.metricCode(), code -> new ArrayList<>())
                        .add(new CandidateWithItem(outcome.index(), candidate));
            }
        }
        Map<String, Candidate> deduped = new LinkedHashMap<>();
        List<Integer> conflictedItems = new ArrayList<>();
        for (Map.Entry<String, List<CandidateWithItem>> entry : byMetric.entrySet()) {
            List<CandidateWithItem> group = entry.getValue();
            Candidate first = group.get(0).candidate();
            boolean allSame = group.stream().allMatch(item -> sameValue(first, item.candidate()));
            if (allSame) {
                deduped.put(entry.getKey(), first);
            } else {
                group.forEach(item -> conflictedItems.add(item.itemIndex()));
            }
        }
        List<ItemOutcome> resolved = new ArrayList<>(outcomes.size());
        for (ItemOutcome outcome : outcomes) {
            if (conflictedItems.contains(outcome.index())) {
                // 同报告同指标出现不同值：该指标整组不沉淀，逐项状态改写为 CONFLICT_SKIPPED
                resolved.add(
                        new ItemOutcome(outcome.index(), contract.itemStates().get("conflict_skipped"), List.of()));
            } else if (outcome.state() != null) {
                // 未映射成功（UNMAPPED/NO_DATE）的项保持原状态，不参与去重
                resolved.add(outcome);
            } else {
                // 完全相同的重复候选只保留首次出现的一条（按实例判定），其余视为已被去重
                List<Candidate> kept = outcome.candidates().stream()
                        .filter(candidate -> deduped.get(candidate.metricCode()) == candidate)
                        .toList();
                resolved.add(new ItemOutcome(
                        outcome.index(), kept.isEmpty() ? contract.itemStates().get("duplicate_slot") : null, kept));
            }
        }
        return resolved;
    }

    private ItemOutcome mapItem(Contracts.HealthObservations contract, JsonNode item, int index, LocalDate observedOn) {
        String name = text(item, "name");
        String referenceRange = normalizeReferenceRange(text(item, "reference_range"));
        String noDate = contract.itemStates().get("no_date");
        String unmapped = contract.itemStates().get("unmapped");
        for (Map.Entry<String, Contracts.HealthObservations.Metric> entry :
                contract.metrics().entrySet()) {
            Contracts.HealthObservations.Metric metric = entry.getValue();
            if (name != null && metric.aliases().contains(name.trim())) {
                if (observedOn == null) {
                    return new ItemOutcome(index, noDate, List.of());
                }
                Candidate candidate = contract.numericValueType().equals(metric.valueType())
                        ? mapNumeric(entry.getKey(), metric, item, referenceRange)
                        : mapCategorical(entry.getKey(), metric, item);
                // 值/单位/分类无法归一：视为未映射，不产生候选（确定性跳过，不抛异常）
                return candidate != null
                        ? new ItemOutcome(index, null, List.of(candidate))
                        : new ItemOutcome(index, unmapped, List.of());
            }
        }
        Contracts.HealthObservations.BloodPressurePair pair = contract.bloodPressurePair();
        if (name != null && pair.aliases().contains(name.trim())) {
            if (observedOn == null) {
                return new ItemOutcome(index, noDate, List.of());
            }
            List<Candidate> split = mapBloodPressure(contract, pair, item, referenceRange);
            return split.isEmpty() ? new ItemOutcome(index, unmapped, List.of()) : new ItemOutcome(index, null, split);
        }
        return new ItemOutcome(index, unmapped, List.of());
    }

    private Candidate mapNumeric(
            String metricCode, Contracts.HealthObservations.Metric metric, JsonNode item, String referenceRange) {
        BigDecimal value = parseNumeric(text(item, "value"));
        if (value == null) {
            return null;
        }
        String unit = normalizeUnit(
                metric.unitAliases(), metric.canonicalUnit(), metric.allowUnitMissing(), text(item, "unit"));
        return unit == null ? null : new Candidate(metricCode, value, null, unit, referenceRange);
    }

    private Candidate mapCategorical(String metricCode, Contracts.HealthObservations.Metric metric, JsonNode item) {
        String category = normalizeCategory(metric, text(item, "value"));
        // 分类观测 unit 必为 NULL（schema ck_health_observations_metric_value_type 强制）
        return category == null ? null : new Candidate(metricCode, null, category, null, null);
    }

    private List<Candidate> mapBloodPressure(
            Contracts.HealthObservations contract,
            Contracts.HealthObservations.BloodPressurePair pair,
            JsonNode item,
            String referenceRange) {
        String value = text(item, "value");
        java.util.regex.Matcher matcher =
                value == null ? null : Pattern.compile(pair.valuePattern()).matcher(value.trim());
        if (matcher == null || !matcher.matches()) {
            return List.of();
        }
        String unit =
                normalizeUnit(pair.unitAliases(), pair.canonicalUnit(), pair.allowUnitMissing(), text(item, "unit"));
        if (unit == null) {
            return List.of();
        }
        return List.of(
                new Candidate(pair.systolicCode(), new BigDecimal(matcher.group(1)), null, unit, referenceRange),
                new Candidate(pair.diastolicCode(), new BigDecimal(matcher.group(2)), null, unit, referenceRange));
    }

    /** 单位归一：经契约 unit_aliases 映射到规范单位；未知单位返回 null（该项不沉淀）。 */
    private String normalizeUnit(
            Map<String, String> unitAliases, String canonicalUnit, boolean allowUnitMissing, String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) {
            return allowUnitMissing ? canonicalUnit : null;
        }
        return unitAliases.get(rawUnit.trim());
    }

    /** 分类值归一：先直查 categories，再经 category_aliases 归一；不在契约内返回 null。 */
    private String normalizeCategory(Contracts.HealthObservations.Metric metric, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String value = rawValue.trim();
        if (metric.categories().contains(value)) {
            return value;
        }
        return metric.categoryAliases().get(value);
    }

    private BigDecimal parseNumeric(String rawValue) {
        if (rawValue == null || !NUMERIC_VALUE.matcher(rawValue.trim()).matches()) {
            return null;
        }
        return new BigDecimal(rawValue.trim());
    }

    private LocalDate parseDate(String rawValue) {
        if (rawValue == null || !ISO_DATE.matcher(rawValue.trim()).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(rawValue.trim());
        } catch (DateTimeParseException invalid) {
            // 形如 2026-13-40 的非法日期：确定性视为缺失，不产生候选
            return null;
        }
    }

    /** 参考范围只抄录原文；"无"/空归一为 NULL（与 seed 形状一致）。 */
    private String normalizeReferenceRange(String rawValue) {
        if (rawValue == null || rawValue.isBlank() || REFERENCE_NONE.equals(rawValue.trim())) {
            return null;
        }
        return rawValue.trim();
    }

    private boolean sameValue(Candidate left, Candidate right) {
        // 去重口径：值 + 单位完全相同（数值按 stripTrailingZeros 比较，58.5 与 58.50 视为同值）
        boolean sameNumeric = left.valueNumeric() == null
                ? right.valueNumeric() == null
                : right.valueNumeric() != null && left.valueNumeric().compareTo(right.valueNumeric()) == 0;
        return sameNumeric
                && java.util.Objects.equals(left.valueCategory(), right.valueCategory())
                && java.util.Objects.equals(left.unit(), right.unit());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record CandidateWithItem(int itemIndex, Candidate candidate) {}

    /** 单个报告项的映射结果：state 非空即契约沉淀状态码（NO_DATE/UNMAPPED/CONFLICT_SKIPPED）；为 null 表示映射成功、候选待沉淀。 */
    public record ItemOutcome(int index, String state, List<Candidate> candidates) {}

    /** 一条沉淀候选：数值与分类恰好一个非空（schema ck_health_observations_value 强制）。 */
    public record Candidate(
            String metricCode, BigDecimal valueNumeric, String valueCategory, String unit, String referenceRange) {}

    /** 整份报告映射结果：candidates 为收敛后的待沉淀清单。 */
    public record ReportMapping(LocalDate observedOn, List<ItemOutcome> items) {

        public List<Candidate> candidates() {
            return items.stream().flatMap(item -> item.candidates().stream()).toList();
        }
    }
}
