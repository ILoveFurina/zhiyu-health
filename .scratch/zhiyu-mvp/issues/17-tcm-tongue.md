# 17 - 中医辨证（拍舌苔）

**What to build:** 患者拍舌苔照片 -> 复用视觉管道 -> 中医体质辨证与调理建议卡片，带免责声明标注。差异化亮点，评审体验项。

**Blocked by:** 12 - 报告解读与视觉管道

**Status:** done

本票是 15 拍皮肤建立的可复制模板的**第二个照搬票**。视觉管道泛化、MinIO 旁路持久化、上传链路、卡片回落会话等 15 已建立的模式直接复用。以下重点记录 17 相对 15/16 的**三条合规差异化边界**（ADR-0024）。

## 差异化点：中医辨证合规边界（ADR-0024）

17 是首个引入中医语义的票，合规负担比 15/16 更重。三条边界由 grilling 确认、ADR-0024 收口：

### 1. 调理建议只讲方向，不出药材

- [x] 中医辨证 prompt 显式约束：体质辨识产出 + 调理方向（作息/运动/饮食原则/通用食材如山药红枣），**禁止**出现具体药材（如黄芩/附子）、方剂名或任何剂量
- [x] prompt 设计需加强约束（与 15/16 的 prompt 不同，需单独编写并明确禁止药材/剂量输出）
- [x] 边界依据：严格落在硬约束 2 与 ADR-0016"通用知识解释"白名单内，不触碰"个性化用药决策"

### 2. 中医专属免责

- [x] `contracts/disclaimer.json` 新增中医专属免责文案（如"体质辨识仅供参考，不替代中医面诊"），与现有通用文案"仅供参考，不替代医生诊断"并列
- [x] 舌诊卡片叠加**通用免责 + 中医免责**两条（其他 AI 产出仍只取通用）
- [x] 双栈同步加载（server-py 注入 + server-java 出口兜底）
- [x] "面诊"是通用文案覆盖不到的中医特定语义，故需专属文案

### 3. 舌象急症软兜底，不扩红线引擎

- [x] 舌象识别出可能指向重病的特征（如镜面舌/霉酱苔）时，由 prompt 引导 LLM 输出"建议尽快就医"软话术
- [x] **不**将视觉结果回流到 `RedFlagRuleEngine` 确定性规则通道；红线引擎维持只接受文本 `judge(String text)` 的现状
- [x] 与 15 拍皮肤"异常描述时建议就医"软兜底先例同构
- [x] 建议仅作调理参考、明确不替代中医面诊的话术（票单原 checklist 第 3 项，已由上述三条覆盖）

## 皮肤模板照搬项（15 已定，此处仅勾选确认）

- [x] `scenarios.py` 注册 `"TONGUE"` key，绑中医辨证 prompt + `TongueAnalysis` result_model
- [x] `AgentClient.java:148` scenario 参数化后传 `"TONGUE"`
- [x] MinIO 旁路持久化：原图存 MinIO + `messages.kind=image` 存路径（15 已建模式）
- [x] 中医辨证结果卡片作为独立 AI 消息回落会话（`kind=tongue_analysis`，落 `messages.content`）
- [x] 免责声明标注（硬规则 1，叠加中医专属免责）
- [x] 会话 composer 加"拍舌苔"入口
- [x] C 端 `index.axml` 加 `tongue_analysis` 卡片渲染分支（优先抽成 `components/tongue-card` 组件）
- [x] `miniprogram/utils/message-kinds.js` 注册 `tongue_analysis` kind
- [x] `contracts/sse-events.json` 的 `message_kinds` 新增 `tongue_analysis`，双端同步
- [x] 功能落地后在票 19 功能入口气泡配置中点亮"拍舌苔"（`feature-bubbles.js` 对应项 `enabled:true` 并接上 action）

## Comments

- 2026-08-04（grilling）：确认 17 照搬 15 模板，但有三条合规差异化边界（ADR-0024）：调理不出药材、中医专属免责、急症软兜底不扩红线。17 是首个引入中医语义的票，比 15/16 合规负担更重。体质辨证本身是中医诊断行为，但调理不出药材使其仍属"通用知识"而非"个性化用药"。
