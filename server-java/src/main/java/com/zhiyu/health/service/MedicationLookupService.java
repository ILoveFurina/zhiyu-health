package com.zhiyu.health.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.mapper.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.HealthProfileMapper;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.rule.ContraindicationFactRepository;
import com.zhiyu.health.rule.ContraindicationFacts;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.rule.ContraindicationRuleEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 拍药盒与文字查药的共用查询 + 规则出口（票 14，ADR-0025 差异化点 2/3）。
 *
 * <p>直接组装票 11 的三个底层原子件，<b>不走</b> {@link ContraindicationService#check}：
 * 后者是 B 端开方入口，会把"已审批处方的药品 ID"并入检查集做跨处方相互作用；14 是 C 端查单药，
 * 不需要这个副作用。故此服务不注入 {@code PrescriptionItemMapper}，彻底避开并入逻辑。
 *
 * <p>三个原子件：
 * <ol>
 *   <li>{@link HealthProfileAllergyMapper#selectAllergens} - 取当前激活档案过敏原
 *   <li>{@link ContraindicationFactRepository#load} - Neo4j 只读拉药品成分 + 禁忌事实
 *   <li>{@link ContraindicationRuleEngine#judge} - 纯函数判定
 * </ol>
 *
 * <p>双出口（ADR-0025 差异化点 3）：一次查询产出两条独立 AI 消息：
 * <ul>
 *   <li>{@code medication_info}：说明书卡片（适应症/用法用量/注意事项来自 medications.instructions）
 *   <li>{@code medication_safety}：安全结果（禁忌决定 safe/blocked/review_required + 引导咨询医生/药师）
 * </ul>
 *
 * <p>无档案优雅降级（空过敏列表）：C 端说明书总是可用，安全检查尽力而为，不像 B 端抛 409。
 * Neo4j 不可用时 fail-closed 退化为 review_required，与 ContraindicationService 同模式。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MedicationLookupService {

    private final ConversationService conversations;
    private final HealthProfileMapper profileMapper;
    private final HealthProfileAllergyMapper allergyMapper;
    private final MedicationMapper medicationMapper;
    private final ContraindicationFactRepository factRepository;
    private final ContraindicationRuleEngine ruleEngine;
    private final ObjectMapper objectMapper;
    private final DisclaimerService disclaimers;

    /**
     * 按候选药名查询药品并做禁忌判定，双出口卡片回落会话。
     *
     * @param candidateNames vision 提取或文字输入的候选药名（商品名/通用名均可）
     * @param title 会话标题（如"拍药盒"/"查药品"），用于 getOrCreateForPatient
     * @return 双出口视图；未匹配任何药品时 medicationInfo/medicationSafety 为 null 且 notFound=true
     */
    public MedicationLookupView lookupAndAppend(
            Long patientId, Long conversationId, String title, List<String> candidateNames) {
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, title);

        // 药名双列查（ADR-0025 差异化点 1）：精确查 name/generic_name，无果再 LIKE 模糊匹配。
        List<Medication> matched = matchMedications(candidateNames);
        if (matched.isEmpty()) {
            // 未匹配任何药品：落一条 text 消息引导用户核对药名或咨询医生/药师，不走规则引擎。
            String names = String.join("、", candidateNames);
            String hint = "未找到药品『" + names + "』，请核对药名或咨询医生/药师。";
            conversations.appendMessage(conversation.getId(), "assistant", hint, Message.KIND_TEXT, null, null, null);
            return new MedicationLookupView(conversation.getId(), null, null, true, disclaimers.text());
        }

        // 取激活档案过敏原；无档案优雅降级为空列表（C 端说明书始终可用，安全检查尽力而为）。
        List<String> allergies = loadAllergens(patientId);

        List<Long> medicationIds = matched.stream().map(Medication::getId).toList();
        ContraindicationResult result = judgeContraindication(allergies, medicationIds);

        // 双出口卡片 1：说明书（medication_info）
        ObjectNode infoCard = buildMedicationInfoCard(matched, candidateNames);
        conversations.appendMessage(
                conversation.getId(), "assistant", infoCard.toString(), Message.KIND_MEDICATION_INFO, null, null, null);

        // 双出口卡片 2：安全结果（medication_safety）
        ObjectNode safetyCard = buildMedicationSafetyCard(matched, result);
        conversations.appendMessage(
                conversation.getId(),
                "assistant",
                safetyCard.toString(),
                Message.KIND_MEDICATION_SAFETY,
                null,
                null,
                null);

        return new MedicationLookupView(
                conversation.getId(), infoCard.get("result"), safetyCard.get("result"), false, disclaimers.text());
    }

    /** 药名双列查：先精确（name 或 generic_name 等值），无果再 LIKE 模糊匹配兜底。 */
    private List<Medication> matchMedications(List<String> candidateNames) {
        // 去重保序：同一药名可能被 vision 多次提取，同一药品可能被商品名和通用名同时命中。
        Map<Long, Medication> dedup = new LinkedHashMap<>();
        List<String> fuzzyCandidates = new ArrayList<>();
        for (String name : candidateNames) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<Medication> exact = medicationMapper.selectActiveByNameOrGeneric(trimmed);
            if (!exact.isEmpty()) {
                for (Medication m : exact) {
                    dedup.put(m.getId(), m);
                }
            } else {
                // 精确无果收集到模糊查候选，稍后批量 LIKE 兜底。
                fuzzyCandidates.add(trimmed);
            }
        }
        for (String name : fuzzyCandidates) {
            String keyword = "%" + name + "%";
            for (Medication m : medicationMapper.selectActiveByNameLike(keyword)) {
                dedup.put(m.getId(), m);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    /** 取激活档案过敏原；无档案优雅降级为空列表。 */
    private List<String> loadAllergens(Long patientId) {
        HealthProfile profile = profileMapper.selectActive(patientId);
        if (profile == null) {
            return List.of();
        }
        return allergyMapper.selectAllergens(profile.getId());
    }

    /**
     * 组装规则引擎三原子件做禁忌判定（ADR-0025 差异化点 2）。
     * Neo4j 不可用时 fail-closed 退化为 review_required，与 ContraindicationService 同模式。
     */
    private ContraindicationResult judgeContraindication(List<String> allergies, List<Long> medicationIds) {
        ContraindicationFacts facts;
        try {
            facts = factRepository.load(medicationIds);
        } catch (RuntimeException unavailable) {
            // 医学事实源不可用时必须 fail closed：不猜测安全，也不把异常细节或患者数据写入日志。
            facts = new ContraindicationFacts(List.of(), List.of(), false);
        }
        return ruleEngine.judge(allergies, medicationIds, facts);
    }

    /** 说明书卡片：medications.instructions 承载适应症/用法用量/注意事项。 */
    private ObjectNode buildMedicationInfoCard(List<Medication> matched, List<String> candidateNames) {
        ObjectNode card = objectMapper.createObjectNode();
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode meds = objectMapper.createArrayNode();
        for (Medication m : matched) {
            ObjectNode med = objectMapper.createObjectNode();
            med.put("name", m.getName());
            med.put("generic_name", m.getGenericName());
            med.put("specification", m.getSpecification());
            med.put("instructions", m.getInstructions());
            meds.add(med);
        }
        result.set("medications", meds);
        ArrayNode names = objectMapper.createArrayNode();
        for (String n : candidateNames) {
            names.add(n);
        }
        result.set("matched_names", names);
        card.set("result", result);
        card.put("disclaimer", disclaimers.text());
        return card;
    }

    /** 安全结果卡片：禁忌决定 + 警告话术 + 引导咨询医生/药师（硬约束 2）。 */
    private ObjectNode buildMedicationSafetyCard(List<Medication> matched, ContraindicationResult result) {
        ObjectNode card = objectMapper.createObjectNode();
        ObjectNode safety = objectMapper.createObjectNode();
        safety.put("decision", result.decision());
        safety.put("message_type", result.messageType());
        safety.put("blocked", result.blocked());
        ArrayNode reasons = objectMapper.createArrayNode();
        for (String r : result.reasons()) {
            reasons.add(r);
        }
        safety.set("reasons", reasons);
        safety.put("message", result.message());
        safety.put("advice", result.advice());
        ArrayNode meds = objectMapper.createArrayNode();
        for (Medication m : matched) {
            ObjectNode med = objectMapper.createObjectNode();
            med.put("name", m.getName());
            med.put("generic_name", m.getGenericName());
            meds.add(med);
        }
        safety.set("medications", meds);
        card.set("result", safety);
        card.put("disclaimer", disclaimers.text());
        return card;
    }

    public record MedicationLookupView(
            @JsonProperty("conversation_id") Long conversationId,
            @JsonProperty("medication_info") JsonNode medicationInfo,
            @JsonProperty("medication_safety") JsonNode medicationSafety,
            @JsonProperty("not_found") boolean notFound,
            String disclaimer) {}
}
