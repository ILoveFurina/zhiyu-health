# 19 - 功能入口气泡与引导卡片

**What to build:** 聊天框上方一排功能入口气泡（AI 诊室/找医院/看报告/拍药盒/拍皮肤/拍饮食/拍舌苔）；点击后 Agent 发出引导卡片，卡片内嵌对应操作组件（找医院->地理位置授权按钮；拍照类->上传图片按钮）。按钮随对应功能落地而点亮，未落地功能不显示入口。

**Blocked by:** 31 - 票 04 拆分迁移；06 - 找医院与地理位置；12 - 报告解读与视觉管道

**Status:** ready-for-agent

- [ ] 入口气泡栏组件（`feature-bubbles.js` 数组 + `enabled` 开关 + `a:for` 渲染，常驻输入框上方）
- [ ] `feature_guide` 客户端渲染消息类型：仅找医院使用，内嵌授权定位按钮，授权后删卡片发对话、拒绝转降级态；不持久化、不带免责声明
- [ ] 至少 AI 诊室 + 找医院 + 看报告三个入口可用：AI诊室发客户端欢迎语聚焦输入框；找医院出引导卡片；看报告复用 `report-composer` ActionSheet
- [ ] 未落地功能（拍药盒/皮肤/饮食/舌苔）入口按 `enabled:false` 隐藏

## Comments

### 2026-07-29 - Grilling 决策记录

施工前先读本节，与上述 checkbox 同等约束力。

- **D1 入口收编**：气泡栏成为唯一功能入口。输入框右侧 `+` 按钮（票 12 临时态）**拆除**；看报告改由"看报告"气泡触发。找医院的关键词触发（`LOCATION_KEYWORDS` 命中走 `my.getLocation`）**保留**，与气泡栏并存--它是自然语言交互而非显式入口，不冲突。
- **D2 引导卡片归属**：订正票 19 原文"Agent 发出引导卡片"。引导卡片由**客户端渲染**为新消息类型 `feature_guide`，**不经 SSE、不经 LLM、不带免责声明**。理由：内嵌操作（定位授权、文件上传）是端能力，非 AI 产出，不进 Agent 循环。仅找医院使用此类型；AI 诊室与看报告不出引导卡片。
- **D3 `feature_guide` 生命周期**：**不持久化**，仅存活于客户端 `messages` 数组。后端 `Message.isAiCardKind` 不纳入；回看历史会话不出现。与 `pendingReport` 待发送卡片同类（UI affordance，非对话内容）。
- **D4 气泡栏位置**：**常驻输入框上方**（`disclaimer-bar` 下、`composer` 上），空态与有消息态均显示，不进 scroll-view。功能入口随时可达，不随首条消息发出而消失。四个未落地入口隐藏后常驻条仅两个气泡，视觉轻量。
- **D5 配置结构**：新建 `pages/chat/feature-bubbles.js`，导出数组 `[{ key, label, icon, enabled, action }]`，`enabled` 为布尔开关。`index.axml` 用 `a:for` 渲染并按 `enabled` 过滤。后续拍照票（14/15/16/17）落地时只改对应项 `enabled:true` 并接 `action`，不动气泡栏本体。`action` 为字符串标识（`'triage'`/`'hospital'`/`'report'`），在 `index.js` dispatch 分发。**不进后端、不进全局 config**--纯 UI 可见性非业务能力开关。
- **D6 AI 诊室行为**：点击插一条客户端预置 assistant 欢迎语（如"请描述您的不适，我帮您判断该挂什么科"）+ 聚焦输入框。**不调 SSE、不持久化**。不发送 `PROMPTS` 里的假症状（避免伪造症状污染对话上下文、浪费 LLM 调用）。
- **D7 找医院行为**：点击出 `feature_guide` 卡片，内嵌「授权定位」按钮，复用 `hospital-routing._locateAndSend`。授权成功：**删卡片** + 插 user 消息「帮我找附近的医院」+ `startRound`(带坐标) -> Agent 回 `hospital_recommendations`（正常 SSE、持久化）。拒绝授权：卡片不删，改文案为降级态「未获取定位，点击按区域查找」，按钮行为改发「我想找医院，请帮我看看附近有哪些科室」，走无坐标 Agent 降级返回 `need_location` 路径。
- **D8 `+` 按钮拆除**为隐含验收项：输入栏仅剩文本输入 + 发送，功能入口全部上移气泡栏。
- **文档落点**：本轮不新增 CONTEXT.md 术语（`feature_guide` 为实现细节、气泡为 UI 元素，均非 ubiquitous language）；不新增 ADR（"客户端渲染而非 Agent 发出"不难逆转、不令人意外、非真实权衡，三判据不全真，跳过）。
