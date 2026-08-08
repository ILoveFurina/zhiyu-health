package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.HealthProfileAllergy;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.mapper.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.HealthProfileMapper;
import com.zhiyu.health.mapper.ReportInterpretationMapper;
import com.zhiyu.health.service.mapping.HealthProfileDtoMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HealthProfileService extends ServiceImpl<HealthProfileMapper, HealthProfile> {

    /** 概要最近报告上限（票 61）：只展示最近若干份 SUCCEEDED 报告。 */
    private static final int OVERVIEW_RECENT_REPORT_LIMIT = 5;

    private final HealthProfileMapper profileMapper;
    private final HealthProfileAllergyMapper allergyMapper;
    private final HealthProfileDtoMapper dtoMapper;
    private final HealthObservationService observations;
    private final ReportInterpretationMapper reportMapper;
    private final Contracts contracts;
    private final DisclaimerService disclaimers;
    private final ObjectMapper objectMapper;

    public List<ProfileView> list(long patientId) {
        return profileMapper
                .selectList(new LambdaQueryWrapper<HealthProfile>()
                        .eq(HealthProfile::getPatientId, patientId)
                        .orderByDesc(HealthProfile::getActive)
                        .orderByAsc(HealthProfile::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public ProfileView current(long patientId) {
        HealthProfile profile = profileMapper.selectActive(patientId);
        return profile == null ? null : toView(profile);
    }

    @Transactional
    public ProfileView create(CreateCommand command) {
        // 清除旧当前档案、新建档案和过敏史必须同事务提交，任一步失败都恢复原服务对象。
        profileMapper.clearActive(command.patientId());
        HealthProfile profile = dtoMapper.toEntity(command);
        profileMapper.insert(profile);
        insertAllergies(profile.getId(), command.allergies());
        return toView(profile);
    }

    @Transactional
    public ProfileView activate(long patientId, long profileId) {
        HealthProfile profile = requireOwned(patientId, profileId);
        // 两步切换与“每位患者仅一个当前档案”的唯一约束同事务收敛，避免留下无当前对象状态。
        profileMapper.clearActive(patientId);
        if (profileMapper.activate(profileId, patientId) != 1) {
            throw new ApiException(409, "健康档案切换失败");
        }
        profile.setActive(true);
        return toView(profile);
    }

    public HealthProfile requireActive(long patientId) {
        HealthProfile profile = profileMapper.selectActive(patientId);
        if (profile == null) {
            throw new ApiException(409, "请先创建健康档案并选择当前服务对象");
        }
        return profile;
    }

    /**
     * 健康档案概要（票 61，ADR-0031）：血型分类卡只放最新有效值，数值指标卡只放有数据项，
     * 趋势仅当有效观测 ≥2 条才非空；有效投影排除 REJECTED/SUPERSEDED；免责声明固定挂载。
     */
    public OverviewView overview(long patientId, long profileId) {
        HealthProfile profile = requireOwned(patientId, profileId);
        List<HealthObservation> effective = observations.effectiveForProfile(patientId, profileId);
        Map<String, List<HealthObservation>> byMetric = new LinkedHashMap<>();
        for (HealthObservation observation : effective) {
            byMetric.computeIfAbsent(observation.getMetricCode(), code -> new ArrayList<>())
                    .add(observation);
        }
        Contracts.HealthObservations contract = contracts.healthObservations();
        List<CategoricalItem> categorical = new ArrayList<>();
        List<MetricItem> metrics = new ArrayList<>();
        // 展示顺序固定为契约声明顺序，避免随数据写入顺序漂移
        for (String metricCode : contract.metricCodes()) {
            List<HealthObservation> rows = byMetric.get(metricCode);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            HealthObservation latest = rows.get(rows.size() - 1);
            if (contract.categoricalValueType().equals(observations.valueType(metricCode))) {
                categorical.add(new CategoricalItem(
                        metricCode,
                        observations.nameZh(metricCode),
                        latest.getValueCategory(),
                        observations.displayValue(latest),
                        latest.getObservedOn(),
                        latest.getSourceType(),
                        latest.getVerificationStatus(),
                        observations.verificationLabel(latest.getVerificationStatus())));
            } else {
                List<TrendPoint> trend = rows.size() >= 2
                        ? rows.stream()
                                .map(row -> new TrendPoint(
                                        row.getId(),
                                        row.getValueNumeric(),
                                        row.getObservedOn(),
                                        row.getVerificationStatus()))
                                .toList()
                        : List.of();
                metrics.add(new MetricItem(
                        metricCode,
                        observations.nameZh(metricCode),
                        latest.getUnit(),
                        new MetricLatest(
                                latest.getId(),
                                latest.getValueNumeric(),
                                latest.getObservedOn(),
                                latest.getSourceType(),
                                latest.getVerificationStatus(),
                                observations.verificationLabel(latest.getVerificationStatus())),
                        trend));
            }
        }
        return new OverviewView(
                new OverviewProfile(
                        profile.getId(),
                        profile.getDisplayName(),
                        profile.getGender(),
                        profile.getBirthDate(),
                        profile.getRelationship()),
                allergyMapper.selectAllergens(profileId),
                List.copyOf(categorical),
                List.copyOf(metrics),
                recentReports(patientId, profileId),
                disclaimers.text());
    }

    /** 单指标观测列表：metric_code 必须在契约白名单，按检查日升序，只含有效投影。 */
    public MetricObservationsView metricObservations(long patientId, long profileId, String metricCode) {
        requireOwned(patientId, profileId);
        Contracts.HealthObservations.Metric metric =
                contracts.healthObservations().metrics().get(metricCode);
        if (metric == null) {
            throw new ApiException(422, "指标代码不在健康观测白名单");
        }
        List<HealthObservationService.ObservationView> items =
                observations.effectiveForProfile(patientId, profileId).stream()
                        .filter(observation -> metricCode.equals(observation.getMetricCode()))
                        .map(observations::toView)
                        .toList();
        return new MetricObservationsView(
                metricCode, metric.nameZh(), metric.valueType(), metric.canonicalUnit(), items, disclaimers.text());
    }

    private List<RecentReport> recentReports(long patientId, long profileId) {
        List<RecentReport> reports = new ArrayList<>();
        for (ReportInterpretation report :
                reportMapper.selectSucceededByProfile(patientId, profileId, OVERVIEW_RECENT_REPORT_LIMIT)) {
            JsonNode result = null;
            try {
                result = report.getResultJson() == null ? null : objectMapper.readTree(report.getResultJson());
            } catch (Exception e) {
                // 历史结果损坏不阻断概要：该报告按无详情处理（确定性降级，不吞其他异常）
                result = null;
            }
            int attention = 0;
            if (result != null) {
                for (JsonNode item : result.path("items")) {
                    String priority = item.path("priority").asText();
                    if ("red".equals(priority) || "yellow".equals(priority)) {
                        attention++;
                    }
                }
            }
            reports.add(new RecentReport(
                    report.getId(),
                    report.getFileName(),
                    result == null ? null : textOrNull(result, "sample_or_exam_date"),
                    result == null ? null : textOrNull(result, "report_date"),
                    result == null ? null : textOrNull(result, "summary"),
                    attention,
                    report.getCreatedAt()));
        }
        return List.copyOf(reports);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public List<TimelineView> timeline(long patientId, long profileId) {
        requireOwned(patientId, profileId);
        return profileMapper.selectTimeline(patientId, profileId).stream()
                .map(dtoMapper::toTimelineView)
                .toList();
    }

    @Transactional
    public ProfileView replaceAllergies(long patientId, long profileId, List<String> allergies) {
        HealthProfile profile = requireOwned(patientId, profileId);
        // 删除与重建过敏史必须原子化，插入失败时保留原过敏史，避免安全信息被部分清空。
        allergyMapper.delete(
                new LambdaQueryWrapper<HealthProfileAllergy>().eq(HealthProfileAllergy::getHealthProfileId, profileId));
        insertAllergies(profileId, allergies);
        return toView(profile);
    }

    public AgentProfileContext agentContext(long patientId) {
        ProfileView profile = current(patientId);
        return profile == null ? null : dtoMapper.toAgentContext(profile);
    }

    public AgentProfileContext agentContext(long patientId, long profileId) {
        return dtoMapper.toAgentContext(toView(requireOwned(patientId, profileId)));
    }

    private void insertAllergies(long profileId, List<String> allergies) {
        for (String allergen : normalizeAllergies(allergies)) {
            allergyMapper.insert(dtoMapper.toAllergy(profileId, allergen));
        }
    }

    private HealthProfile requireOwned(long patientId, long profileId) {
        HealthProfile profile = profileMapper.selectOwned(profileId, patientId);
        if (profile == null) {
            throw new ApiException(404, "健康档案不存在");
        }
        return profile;
    }

    private ProfileView toView(HealthProfile profile) {
        return dtoMapper.toView(profile, allergyMapper.selectAllergens(profile.getId()));
    }

    private List<String> normalizeAllergies(List<String> allergies) {
        if (allergies == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String allergy : allergies) {
            if (allergy != null && !allergy.isBlank()) {
                normalized.add(allergy.trim());
            }
        }
        return List.copyOf(normalized);
    }

    public record CreateCommand(
            long patientId,
            String displayName,
            String gender,
            LocalDate birthDate,
            String relationship,
            List<String> allergies) {}

    public record ProfileView(
            Long id,
            @JsonProperty("display_name") String displayName,
            String gender,
            @JsonProperty("birth_date") LocalDate birthDate,
            String relationship,
            boolean active,
            List<String> allergies) {}

    public record TimelineView(
            String type,
            @JsonProperty("record_id") Long recordId,
            String title,
            String summary,
            @JsonProperty("occurred_at") String occurredAt,
            String disclaimer) {}

    public record AgentProfileContext(
            Long id,
            @JsonProperty("display_name") String displayName,
            String gender,
            @JsonProperty("birth_date") LocalDate birthDate,
            String relationship,
            List<String> allergies) {}

    /** 档案概要（票 61）：categorical 只放血型类最新有效值；metrics 无数据不生成空卡；disclaimer 固定挂载。 */
    public record OverviewView(
            OverviewProfile profile,
            List<String> allergies,
            List<CategoricalItem> categorical,
            List<MetricItem> metrics,
            @JsonProperty("recent_reports") List<RecentReport> recentReports,
            String disclaimer) {}

    public record OverviewProfile(
            Long id,
            @JsonProperty("display_name") String displayName,
            String gender,
            @JsonProperty("birth_date") LocalDate birthDate,
            String relationship) {}

    public record CategoricalItem(
            @JsonProperty("metric_code") String metricCode,
            @JsonProperty("name_zh") String nameZh,
            String value,
            @JsonProperty("display_value") String displayValue,
            @JsonProperty("observed_on") LocalDate observedOn,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("verification_status") String verificationStatus,
            @JsonProperty("verification_label") String verificationLabel) {}

    public record MetricItem(
            @JsonProperty("metric_code") String metricCode,
            @JsonProperty("name_zh") String nameZh,
            String unit,
            MetricLatest latest,
            List<TrendPoint> trend) {}

    public record MetricLatest(
            @JsonProperty("observation_id") Long observationId,
            BigDecimal value,
            @JsonProperty("observed_on") LocalDate observedOn,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("verification_status") String verificationStatus,
            @JsonProperty("verification_label") String verificationLabel) {}

    public record TrendPoint(
            @JsonProperty("observation_id") Long observationId,
            BigDecimal value,
            @JsonProperty("observed_on") LocalDate observedOn,
            @JsonProperty("verification_status") String verificationStatus) {}

    public record RecentReport(
            Long id,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("exam_date") String examDate,
            @JsonProperty("report_date") String reportDate,
            String summary,
            @JsonProperty("attention_count") int attentionCount,
            @JsonProperty("created_at") OffsetDateTime createdAt) {}

    public record MetricObservationsView(
            @JsonProperty("metric_code") String metricCode,
            @JsonProperty("name_zh") String nameZh,
            @JsonProperty("value_type") String valueType,
            String unit,
            List<HealthObservationService.ObservationView> observations,
            String disclaimer) {}
}
