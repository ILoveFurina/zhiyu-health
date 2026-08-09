package com.zhiyu.health.rule;

import java.util.List;
import org.springframework.stereotype.Component;

/** 红线症状确定性规则引擎；规则判断必须先于任何 Agent 调用。 */
@Component
public class RedFlagRuleEngine {

    private static final String ADVICE = "请立即就近就医，或拨打 120 急救电话";

    private static final List<Rule> RULES = List.of(
            new Rule("胸痛伴冷汗（疑似心梗）", List.of(List.of("胸痛", "胸口痛", "胸口疼"), List.of("冷汗", "出冷汗", "大汗淋漓"))),
            new Rule("意识障碍", List.of(List.of("意识模糊", "昏迷", "失去意识", "昏厥", "叫不醒"))),
            new Rule("呼吸窘迫", List.of(List.of("呼吸困难", "喘不上气", "无法呼吸", "窒息"))),
            new Rule("中风征兆", List.of(List.of("口角歪斜", "半身不遂", "一侧肢体无力", "半边身子无力", "偏瘫"))),
            new Rule("大出血/呕血咯血", List.of(List.of("大出血", "呕血", "吐血", "咯血", "便血不止"))),
            new Rule("持续抽搐", List.of(List.of("抽搐不止", "持续抽搐", "全身抽搐", "抽搐停不下"))),
            new Rule("急性中毒", List.of(List.of("服毒", "农药中毒", "喝了农药", "服了农药", "误服农药", "煤气中毒", "一氧化碳中毒"))));

    public RedFlagHit judge(String text) {
        String compact = text.replaceAll("\\s+", "");
        return RULES.stream()
                .filter(rule ->
                        rule.groups().stream().allMatch(group -> group.stream().anyMatch(compact::contains)))
                .findFirst()
                .map(rule -> new RedFlagHit(rule.name(), ADVICE))
                .orElse(null);
    }

    private record Rule(String name, List<List<String>> groups) {}
}
