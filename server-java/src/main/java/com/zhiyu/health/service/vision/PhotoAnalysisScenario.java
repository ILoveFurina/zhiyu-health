package com.zhiyu.health.service.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.chat.Message;

/** 三种会话分析场景的真实差异；公共上传、存储、调用和落库顺序由管道统一维护。 */
enum PhotoAnalysisScenario {
    SKIN("皮肤", "拍皮肤", "SKIN", Message.KIND_SKIN_ANALYSIS, "皮肤分析服务暂不可用", "皮肤分析结果损坏", false),
    DIET("饮食", "拍饮食", "DIET", Message.KIND_DIET_ANALYSIS, "饮食分析服务暂不可用", "饮食分析结果损坏", false),
    TONGUE("舌苔", "拍舌苔", "TONGUE", Message.KIND_TONGUE_ANALYSIS, "舌苔辨证服务暂不可用", "舌苔辨证结果损坏", true);

    private final String photoName;
    private final String title;
    private final String agentScenario;
    private final String messageKind;
    private final String unavailableMessage;
    private final String corruptMessage;
    private final boolean tcm;

    PhotoAnalysisScenario(
            String photoName,
            String title,
            String agentScenario,
            String messageKind,
            String unavailableMessage,
            String corruptMessage,
            boolean tcm) {
        this.photoName = photoName;
        this.title = title;
        this.agentScenario = agentScenario;
        this.messageKind = messageKind;
        this.unavailableMessage = unavailableMessage;
        this.corruptMessage = corruptMessage;
        this.tcm = tcm;
    }

    ObjectNode fallback(ObjectMapper mapper, AgentClient.VisionAgentException error) {
        ObjectNode result = mapper.createObjectNode();
        String hint = scopeUnsupported(error) ? scopeHint() : unavailableHint();
        switch (this) {
            case SKIN -> {
                result.put("skin_type", "未能完成分析");
                result.putArray("findings");
                result.put("care_summary", hint);
            }
            case DIET -> {
                result.put("meal_type", "未能完成分析");
                result.putArray("foods");
                result.put("estimated_calories", "无法估量");
                result.put("nutrition_summary", "未能完成分析");
                result.put("diet_advice", hint);
                result.put("personal_tip", "");
            }
            case TONGUE -> {
                result.put("constitution", "未能完成辨证");
                result.put("tongue_features", "未能完成分析");
                result.put("care_direction", "未能完成分析");
                result.put("diet_principle", "未能完成分析");
                result.put("urgency_hint", hint);
            }
        }
        result.put("need_doctor", true);
        return result;
    }

    private boolean scopeUnsupported(AgentClient.VisionAgentException error) {
        return error != null && ("VISION_" + agentScenario + "_SCOPE_UNSUPPORTED").equals(error.code());
    }

    private String scopeHint() {
        return "请上传清晰的" + photoName + "照片，暂不支持医学影像或报告诊断。";
    }

    private String unavailableHint() {
        return switch (this) {
            case SKIN -> "皮肤分析暂不可用，如皮肤有明显不适请及时就医。";
            case DIET -> "饮食分析暂不可用，如有特殊饮食需求请咨询医生或营养师。";
            case TONGUE -> "舌苔辨证暂不可用，如舌象明显异常请尽快就医，由中医面诊确认。";
        };
    }

    String photoName() {
        return photoName;
    }

    String title() {
        return title;
    }

    String agentScenario() {
        return agentScenario;
    }

    String messageKind() {
        return messageKind;
    }

    String unavailableMessage() {
        return unavailableMessage;
    }

    String corruptMessage() {
        return corruptMessage;
    }

    boolean tcm() {
        return tcm;
    }
}
