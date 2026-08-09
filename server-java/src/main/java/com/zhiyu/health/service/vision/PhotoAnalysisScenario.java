package com.zhiyu.health.service.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.chat.Message;

/** 四种会话分析场景的真实差异；公共上传、存储、调用和失败出口由管道统一维护。 */
enum PhotoAnalysisScenario {
    SKIN(
            "皮肤",
            "拍皮肤",
            "SKIN",
            Message.KIND_SKIN_ANALYSIS,
            "皮肤分析服务暂不可用",
            "皮肤分析结果损坏",
            "皮肤分析暂不可用，如皮肤有明显不适请及时就医。",
            false,
            false) {
        @Override
        void populateFallback(ObjectNode result, String hint) {
            result.put("skin_type", "未能完成分析");
            result.putArray("findings");
            result.put("care_summary", hint);
        }
    },
    DIET(
            "饮食",
            "拍饮食",
            "DIET",
            Message.KIND_DIET_ANALYSIS,
            "饮食分析服务暂不可用",
            "饮食分析结果损坏",
            "饮食分析暂不可用，如有特殊饮食需求请咨询医生或营养师。",
            false,
            false) {
        @Override
        void populateFallback(ObjectNode result, String hint) {
            result.put("meal_type", "未能完成分析");
            result.putArray("foods");
            result.put("estimated_calories", "无法估量");
            result.put("nutrition_summary", "未能完成分析");
            result.put("diet_advice", hint);
            result.put("personal_tip", "");
        }
    },
    TONGUE(
            "舌苔",
            "拍舌苔",
            "TONGUE",
            Message.KIND_TONGUE_ANALYSIS,
            "舌苔辨证服务暂不可用",
            "舌苔辨证结果损坏",
            "舌苔辨证暂不可用，如舌象明显异常请尽快就医，由中医面诊确认。",
            true,
            false) {
        @Override
        void populateFallback(ObjectNode result, String hint) {
            result.put("constitution", "未能完成辨证");
            result.put("tongue_features", "未能完成分析");
            result.put("care_direction", "未能完成分析");
            result.put("diet_principle", "未能完成分析");
            result.put("urgency_hint", hint);
        }
    },
    PILL_BOX(
            "药盒",
            "拍药盒",
            "PILL_BOX",
            Message.KIND_TEXT,
            "药盒识别服务暂不可用",
            "药盒识别结果损坏",
            "药盒识别暂不可用，请重拍或直接输入药名，也可咨询医生或药师。",
            false,
            true) {
        @Override
        void populateFallback(ObjectNode result, String hint) {
            result.put("hint", hint);
        }
    };

    private final String photoName;
    private final String title;
    private final String agentScenario;
    private final String messageKind;
    private final String unavailableMessage;
    private final String corruptMessage;
    private final String unavailableHint;
    private final boolean tcm;
    private final boolean parallelStorage;

    PhotoAnalysisScenario(
            String photoName,
            String title,
            String agentScenario,
            String messageKind,
            String unavailableMessage,
            String corruptMessage,
            String unavailableHint,
            boolean tcm,
            boolean parallelStorage) {
        this.photoName = photoName;
        this.title = title;
        this.agentScenario = agentScenario;
        this.messageKind = messageKind;
        this.unavailableMessage = unavailableMessage;
        this.corruptMessage = corruptMessage;
        this.unavailableHint = unavailableHint;
        this.tcm = tcm;
        this.parallelStorage = parallelStorage;
    }

    ObjectNode fallback(ObjectMapper mapper, AgentClient.VisionAgentException error) {
        ObjectNode result = mapper.createObjectNode();
        populateFallback(result, scopeUnsupported(error) ? scopeHint() : unavailableHint);
        result.put("need_doctor", true);
        return result;
    }

    abstract void populateFallback(ObjectNode result, String hint);

    private boolean scopeUnsupported(AgentClient.VisionAgentException error) {
        return error != null && ("VISION_" + agentScenario + "_SCOPE_UNSUPPORTED").equals(error.code());
    }

    private String scopeHint() {
        return "请上传清晰的" + photoName + "照片，暂不支持医学影像或报告诊断。";
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

    boolean parallelStorage() {
        return parallelStorage;
    }
}
