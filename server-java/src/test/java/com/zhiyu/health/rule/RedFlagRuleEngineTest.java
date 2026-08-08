package com.zhiyu.health.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 红线规则 seam：危险输入必触发，普通症状不误触。 */
class RedFlagRuleEngineTest {

    private final RedFlagRuleEngine engine = new RedFlagRuleEngine();

    @ParameterizedTest
    @ValueSource(
            strings = {"我突然胸痛，还出冷汗", "老人突然昏迷叫不醒", "喘不上气，感觉呼吸困难", "刚才吐血了", "孩子全身抽搐停不下来", "误服了农药，现在很难受", "突然口角歪斜，半边身子动不了"
            })
    void dangerousInputMustHit(String text) {
        RedFlagHit hit = engine.judge(text);

        assertThat(hit).isNotNull();
        assertThat(hit.advice()).contains("120");
    }

    @ParameterizedTest
    @ValueSource(strings = {"有点咳嗽，嗓子痒", "头疼两天了，不是很厉害", "感冒发烧38度，浑身没劲", "胸闷，不知道是不是天气原因", "胸口有点痛，就一下"})
    void normalSymptomsMustNotHit(String text) {
        assertThat(engine.judge(text)).isNull();
    }

    @Test
    void chestPainWithColdSweatNamesTheRule() {
        RedFlagHit hit = engine.judge("胸口痛，还一直出冷汗");

        assertThat(hit).isNotNull();
        assertThat(hit.ruleName()).contains("胸痛");
    }
}
