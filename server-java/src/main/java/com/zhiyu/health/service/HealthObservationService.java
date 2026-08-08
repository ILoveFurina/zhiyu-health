package com.zhiyu.health.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import com.zhiyu.health.service.mapping.HealthObservationDtoMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 健康观测状态机（票 61，ADR-0031）：
 * - 归属：所有操作经观测 JOIN health_profiles 校验 patient_id，查不到抛 404（不泄露存在性）；
 * - confirm/reject：条件 UPDATE 收敛并发与幂等，影响 0 行再查分流（幂等返回 / 404 / 409）；
 * - correct：追加式纠错——条件 UPDATE 旧记录 SUPERSEDED/current=FALSE + INSERT USER_CORRECTION 新记录，
 *   可对纠错结果再次纠错形成链；日期、指标代码、单位、来源报告不可改；
 * - 有效投影只读 current 且 UNVERIFIED/USER_CONFIRMED，REJECTED/SUPERSEDED 不进概要/趋势。
 */
@Service
@RequiredArgsConstructor
public class HealthObservationService extends ServiceImpl<HealthObservationMapper, HealthObservation> {

    // 与 HealthObservationMapping 同一纪律：数值先格式校验再构造，坏值抛 422 而非依赖异常控制流。
    private static final Pattern NUMERIC_VALUE = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private final HealthObservationMapper observationMapper;
    private final Contracts contracts;
    private final DisclaimerService disclaimers;
    private final HealthObservationDtoMapper dtoMapper;

    /** 有效投影：概要/趋势/指标列表共用。 */
    public List<HealthObservation> effectiveForProfile(long patientId, long profileId) {
        return observationMapper.selectEffective(patientId, profileId, unverified(), userConfirmed());
    }

    /** 报告详情的沉淀状态推导数据源（含历史版本，由调用方按 current/状态推导）。 */
    public List<HealthObservation> forReport(long reportId) {
        return observationMapper.selectByReport(reportId);
    }

    /** 确认：UNVERIFIED → USER_CONFIRMED；重复确认为幂等返回。 */
    public ObservationView confirm(long patientId, long observationId) {
        requireOwned(patientId, observationId);
        int affected = observationMapper.confirm(observationId, userConfirmed(), unverified());
        if (affected == 0) {
            HealthObservation current = observationMapper.selectById(observationId);
            if (isCurrentWithStatus(current, userConfirmed())) {
                return toView(current);
            }
            throw conflictOrGone(current, "观测当前状态不可确认");
        }
        return toView(observationMapper.selectById(observationId));
    }

    /**
     * 纠错：校验新值后在同一事务内抢占旧记录（0 行 → 409 并发冲突）并追加 USER_CORRECTION 新记录。
     * 槽位唯一索引兜底并发插入，冲突翻译为 409，事务整体回滚不留半提交链。
     */
    @Transactional
    public ObservationView correct(CorrectCommand command) {
        HealthObservation existing = requireOwned(command.patientId(), command.observationId());
        if (!Boolean.TRUE.equals(existing.getCurrent())) {
            throw new ApiException(404, "健康观测不存在");
        }
        if (!correctable(existing.getVerificationStatus())) {
            throw new ApiException(409, "观测当前状态不可纠错");
        }
        Contracts.HealthObservations.Metric metric = metric(existing.getMetricCode());
        BigDecimal numeric = null;
        String category = null;
        if (contracts.healthObservations().numericValueType().equals(metric.valueType())) {
            numeric = parseNumeric(command.value());
        } else {
            category = normalizeCategory(metric, command.value());
        }
        int affected = observationMapper.supersede(existing.getId(), superseded(), unverified(), userConfirmed());
        if (affected == 0) {
            throw new ApiException(409, "观测已被其他操作更新，请刷新后重试");
        }
        HealthObservation correction = new HealthObservation();
        correction.setHealthProfileId(existing.getHealthProfileId());
        correction.setReportInterpretationId(existing.getReportInterpretationId());
        correction.setMetricCode(existing.getMetricCode());
        correction.setValueNumeric(numeric);
        correction.setValueCategory(category);
        correction.setUnit(existing.getUnit());
        correction.setReferenceRange(existing.getReferenceRange());
        correction.setObservedOn(existing.getObservedOn());
        correction.setSourceType(userCorrection());
        correction.setVerificationStatus(userConfirmed());
        correction.setCurrent(true);
        correction.setSupersedesId(existing.getId());
        try {
            observationMapper.insert(correction);
        } catch (DataIntegrityViolationException slotTaken) {
            // 每日槽位被并发写入抢占：部分唯一索引兜底，翻译为 409 由调用方刷新重试
            throw new ApiException(409, "该日期已有档案记录，请刷新后重试");
        }
        return toView(correction);
    }

    /** 排除：终态 REJECTED，保持 current=TRUE 占用每日槽位；重复排除为幂等返回。 */
    public ObservationView reject(long patientId, long observationId) {
        requireOwned(patientId, observationId);
        int affected = observationMapper.reject(observationId, rejected(), unverified(), userConfirmed());
        if (affected == 0) {
            HealthObservation current = observationMapper.selectById(observationId);
            if (isCurrentWithStatus(current, rejected())) {
                return toView(current);
            }
            throw conflictOrGone(current, "观测当前状态不可排除");
        }
        return toView(observationMapper.selectById(observationId));
    }

    public String nameZh(String metricCode) {
        return metric(metricCode).nameZh();
    }

    public String valueType(String metricCode) {
        return metric(metricCode).valueType();
    }

    /** 展示值：数值去尾零，分类取契约中文文案。 */
    public String displayValue(HealthObservation observation) {
        if (observation.getValueCategory() != null) {
            Contracts.HealthObservations.Metric metric = metric(observation.getMetricCode());
            return metric.categoryDisplayZh()
                    .getOrDefault(observation.getValueCategory(), observation.getValueCategory());
        }
        return observation.getValueNumeric() == null
                ? null
                : observation.getValueNumeric().stripTrailingZeros().toPlainString();
    }

    public String sourceLabel(String sourceType) {
        return contracts.healthObservations().sourceDisplayZh().getOrDefault(sourceType, sourceType);
    }

    public String verificationLabel(String verificationStatus) {
        return contracts
                .healthObservations()
                .verificationDisplayZh()
                .getOrDefault(verificationStatus, verificationStatus);
    }

    public String itemStateLabel(String itemState) {
        return contracts.healthObservations().itemStateDisplayZh().getOrDefault(itemState, itemState);
    }

    public String disclaimer() {
        return disclaimers.text();
    }

    public ObservationView toView(HealthObservation observation) {
        return dtoMapper.toView(
                observation,
                nameZh(observation.getMetricCode()),
                valueType(observation.getMetricCode()),
                displayValue(observation),
                sourceLabel(observation.getSourceType()),
                verificationLabel(observation.getVerificationStatus()));
    }

    private HealthObservation requireOwned(long patientId, long observationId) {
        HealthObservation observation = observationMapper.selectOwned(observationId, patientId);
        if (observation == null) {
            throw new ApiException(404, "健康观测不存在");
        }
        return observation;
    }

    private boolean correctable(String verificationStatus) {
        return unverified().equals(verificationStatus) || userConfirmed().equals(verificationStatus);
    }

    private boolean isCurrentWithStatus(HealthObservation observation, String status) {
        return observation != null
                && Boolean.TRUE.equals(observation.getCurrent())
                && status.equals(observation.getVerificationStatus());
    }

    /** 条件更新 0 行分流：历史版本（current=FALSE）视为 404，其余终态为 409。 */
    private ApiException conflictOrGone(HealthObservation current, String conflictMessage) {
        if (current == null || !Boolean.TRUE.equals(current.getCurrent())) {
            return new ApiException(404, "健康观测不存在");
        }
        return new ApiException(409, conflictMessage);
    }

    private BigDecimal parseNumeric(String rawValue) {
        if (rawValue == null || !NUMERIC_VALUE.matcher(rawValue.trim()).matches()) {
            throw new ApiException(422, "观测值必须为数字");
        }
        return new BigDecimal(rawValue.trim());
    }

    private String normalizeCategory(Contracts.HealthObservations.Metric metric, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new ApiException(422, "观测分类值无效");
        }
        String value = rawValue.trim();
        if (metric.categories().contains(value)) {
            return value;
        }
        String normalized = metric.categoryAliases().get(value);
        if (normalized == null) {
            throw new ApiException(422, "观测分类值无效");
        }
        return normalized;
    }

    private Contracts.HealthObservations.Metric metric(String metricCode) {
        Contracts.HealthObservations.Metric metric =
                contracts.healthObservations().metrics().get(metricCode);
        if (metric == null) {
            // 库内数据均经契约白名单写入；读到契约外指标属数据损坏，不按业务错误处理
            throw new IllegalStateException("健康观测指标不在契约白名单: " + metricCode);
        }
        return metric;
    }

    private String unverified() {
        return contracts.healthObservations().unverifiedStatus();
    }

    private String userConfirmed() {
        return contracts.healthObservations().userConfirmedStatus();
    }

    private String rejected() {
        return contracts.healthObservations().rejectedStatus();
    }

    private String superseded() {
        return contracts.healthObservations().supersededStatus();
    }

    private String userCorrection() {
        return contracts.healthObservations().userCorrectionSource();
    }

    public record CorrectCommand(long patientId, long observationId, String value) {}

    public record ObservationView(
            Long id,
            @JsonProperty("metric_code") String metricCode,
            @JsonProperty("name_zh") String nameZh,
            @JsonProperty("value_type") String valueType,
            @JsonProperty("value_numeric") BigDecimal valueNumeric,
            @JsonProperty("value_category") String valueCategory,
            @JsonProperty("display_value") String displayValue,
            String unit,
            @JsonProperty("reference_range") String referenceRange,
            @JsonProperty("observed_on") LocalDate observedOn,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_label") String sourceLabel,
            @JsonProperty("verification_status") String verificationStatus,
            @JsonProperty("verification_label") String verificationLabel,
            @JsonProperty("report_interpretation_id") Long reportInterpretationId) {}
}
