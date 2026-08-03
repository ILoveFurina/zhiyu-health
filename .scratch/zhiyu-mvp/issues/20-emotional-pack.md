# 20 - 情感化包（情绪/关怀/语音/就诊指引卡）

> **Superseded by:** 票 43（挂号后关怀消息 + 就诊指引卡）、票 44（emotion 情感化核心）、票 45（语音双向 ASR+TTS）。grill-with-docs 会话（2026-08-03）将本票拆为三票：43 承接"挂号后主动关怀消息"+"就诊指引卡"，44 承接"emotion+气泡变色+安抚文案"，45 承接"语音输入 ASR"+"AI 回复 TTS"。拆分理由：一票五事违反一票一分支、外部依赖不同步（语音需火山服务开通前置而本栈情感化无此依赖）、blocker 拓扑不同。设计决策见三票 Comments 与 ADR-0019/0020、CONTEXT.md 新增术语。

**What to build:** 情感化（创新方向 A）集中落地：LLM 结构化输出 emotion 字段，焦虑时 UI 气泡变色 + 安抚文案；挂号成功后主动关怀消息（就诊注意事项，站内）；语音输入（`my.getRecorderManager` 按住说话录音 -> 火山引擎语音识别成文字，需提前开通火山语音服务并配置密钥）；AI 回复 TTS 语音播报（火山引擎）；挂号成功后发就诊指引卡（地址/楼层/注意事项/携带材料）。

**Blocked by:** 07 - 挂号闭环；09 - 电子处方（站内消息通道）

**Status:** retired - superseded-by-43-44-45

- [ ] emotion 决定值和 SSE/消息类型从 `contracts/` 推导，server-py 输出结构化 emotion，server-java 透传并兜底校验，小程序据此驱动 UI 情绪反馈
- [ ] 固定 3 条焦虑表达样例及期望 emotion/UI/安抚文案，三条均通过方可验收
- [ ] 挂号后主动关怀站内消息
- [ ] 文档写明火山引擎语音识别/TTS 的服务开通、模型/音色、格式限制和本地配置前置；实现时对照锁定 SDK/HTTP 协议的官方文档，不记录或输出密钥
- [ ] 语音输入由支付宝 `my.getRecorderManager` 采集后经 server-java 转发 server-py，再由 server-py 调用火山引擎识别；未配置、超时或识别失败时明确降级为文字输入且不阻塞演示
- [ ] TTS 仅由 server-py 调用火山引擎生成，server-java 逐跳返回，小程序支持播放/停止；失败时保留文字回复
- [ ] 就诊指引卡随挂号成功发出

## Comments

- 2026-07-29：删除与支付宝技术栈不符的 WechatSI 插件描述，统一为“支付宝录音 + server-java 入口 + server-py 调火山引擎 ASR/TTS”。
- 2026-08-03：grill-with-docs 会话将本票拆为 43/44/45 三票，本票 retired。原五项能力分配：43 承接挂号后关怀消息 + 就诊指引卡，44 承接 emotion 气泡变色 + 安抚文案，45 承接语音输入 ASR + AI 回复 TTS。设计决策记录于三票 Comments、ADR-0019（emotion 串行二次调用）、ADR-0020（ASR/TTS 不进 trace）、CONTEXT.md 新增"就诊指引卡/情绪反馈/安抚语"三条术语。
