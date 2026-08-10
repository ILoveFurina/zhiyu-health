package com.zhiyu.health.service.prescription;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.health.HealthProfile;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.mapper.health.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.health.HealthProfileMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.rule.ContraindicationFactRepository;
import com.zhiyu.health.rule.ContraindicationFacts;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.rule.ContraindicationRuleEngine;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 就诊上下文健康档案禁忌检查：PG 提供可信业务上下文，Neo4j 提供唯一医学事实。 */
@Service
@RequiredArgsConstructor
public class ContraindicationService {

    private final HealthProfileMapper profileMapper;
    private final HealthProfileAllergyMapper allergyMapper;
    private final MedicationMapper medicationMapper;
    private final PrescriptionItemMapper prescriptionItemMapper;
    private final ContraindicationFactRepository factRepository;
    private final ContraindicationRuleEngine ruleEngine;
    private final Contracts contracts;

    public ContraindicationResult check(CheckCommand command) {
        List<Long> medicationIds = List.copyOf(new LinkedHashSet<>(command.medicationIds()));
        // 档案取就诊上下文固化的 health_profile_id（与开方上下文、票 97 接诊详情同一来源），
        // 不取「当前活跃档案」：患者可能建有多个档案，挂号/问诊绑定的与活跃的不一致时，
        // 用活跃档案会读错过敏史导致禁忌漏检（票 93 复盘，青宸/青 复现）。
        HealthProfile profile = profileMapper.selectOwned(command.healthProfileId(), command.patientId());
        if (profile == null) {
            throw new ApiException(409, "请先创建健康档案后再进行禁忌检查");
        }

        // 候选 + 档案在用药（APPROVED 处方药品）并集一并加载：既校验候选存在，也为规则引擎
        // 提供 PG 权威药名（命中 reason 只展示全名，不暴露裸 id，票 93）。
        List<Long> approvedMedicationIds = prescriptionItemMapper.selectMedicationIdsByHealthProfileAndStatus(
                profile.getId(), contracts.prescriptionFlow().statuses().get("approved"));
        LinkedHashSet<Long> checkedMedicationIds = new LinkedHashSet<>(medicationIds);
        checkedMedicationIds.addAll(approvedMedicationIds);
        List<Medication> medications = medicationMapper.selectByIds(List.copyOf(checkedMedicationIds));
        // 票 88：medications 收敛为标准目录（无上下架语义），存在即可参与禁忌检查；
        // 在售/库存校验在开方目录与下单事务各自完成。
        Set<Long> existingIds =
                medications.stream().map(Medication::getId).collect(java.util.stream.Collectors.toSet());
        for (Long medicationId : medicationIds) {
            if (!existingIds.contains(medicationId)) {
                throw new ApiException(400, "药品不存在: " + medicationId);
            }
        }

        Map<Long, String> medicationNames = medications.stream()
                .filter(m -> m.getName() != null)
                .collect(java.util.stream.Collectors.toMap(Medication::getId, Medication::getName));
        List<String> allergies = allergyMapper.selectAllergens(profile.getId());
        ContraindicationFacts facts;
        try {
            facts = factRepository.load(List.copyOf(checkedMedicationIds));
        } catch (RuntimeException unavailable) {
            // 医学事实源不可用时必须 fail closed：不猜测安全，也不把异常细节或患者数据写入日志。
            facts = new ContraindicationFacts(List.of(), List.of(), false);
        }
        return ruleEngine.judge(allergies, medicationIds, facts, medicationNames);
    }

    public record CheckCommand(long patientId, long healthProfileId, List<Long> medicationIds) {}
}
