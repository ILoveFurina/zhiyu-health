# 67 - C 端 UI/UX 二期（首页 IA 重排 / 记忆点动画 / 弱页翻新 / 功能页信息分层）

**What to build:** 合并票（原 67/68/72/73 四票合一，决议见 Comments）。纯 axml/acss + 组件 props，不动业务逻辑，消费票 66 的 token/图标/空态组件。**四段按序施工，每段独立 commit（回退颗粒度在 commit 级）；时间不够从第 4 段起往尾部砍，未做段注明口径后票仍可置 done（票 27 先例）。**

**第 1 段 · 首页信息架构重排（原票 72）**

1. 待办横卡从页面末尾上移到问候头与主卡之间，有待办即首屏第一块内容；无待办整块不渲染（现状逻辑保留）。
2. registration-card 主卡瘦身：移除卡内 3 家医院列表（低频目录不占首屏），改一行轻入口「{城市} · 附近 {N} 家医院 ›」navigateTo 医院列表页；「重新校正位置」随之移出，由 hospitals 页定位流程承接。主卡只留标题 + 科室挂号/智能导诊双按钮。
3. registration-card 医院列表改可选渲染（props/slot），home 与 chat 空态统一精简模式——chat 空态"推荐词卡 + 主卡"堆叠过长同源解决；全量模式无消费方则删除。
4. 宫格不动（CONTEXT.md"功能目录"：宫格与 Agent 卡片入口并存是领域决策）。

**第 2 段 · chat 记忆点动画与输入区净化（原票 67 + 2026-08-09 扩展）**

1. chat：AI 气泡入场 fade + rise；业务卡（doctor-card / department-slots-card / hospital-card / appointment-card）入场依次浮入。**流式光标与工具进度条归票 70，本票不碰**；动画只挂消息/卡片挂载时刻，不干扰 SSE token 追加路径；红线卡、免责声明条即时可见不参与入场延迟。
2. home：主卡 + 宫格入场浮入，仅首次入场播放。
3. consult/waiting：保留 spinner，倒计时加轻微脉搏动效。
4. **常驻免责横条移除**：删除 chat 底部 `.disclaimer-bar` 常驻细条（原注释"双保险之二"）与对应 acss，preconsult 同款处理。每消息/每卡片 ai-disclaimer 保留——硬约束 1 的载体不变（spec 0002 有合规论证）。
5. **语音提示条内联化**：voiceHint 全宽横条（录音中/识别中/失败）退役，三态内联进输入行——话筒按钮录音脉冲、识别/失败文案入输入框内部；排查修复语音状态未清除的卡死（非语音场景横条残留）。票 70"toolProgress 类留 voiceHint"约定由本项取代。
6. **气泡栏滚动条隐藏**：功能入口气泡栏（`.bubble-bar`，现为 view + `overflow-x: auto`，模拟器露出底部横滚轮）改为 `scroll-view scroll-x` + `show-scrollbar="{{false}}"`，对齐首页待办横卡/排期日期条先例；chips nowrap 布局保持。
7. **页内次级 header**：绝对定位悬浮 ≡ 按钮退役，chat 页顶部加页内 header——左侧当前会话标题（新对话显示「智能导诊」），右侧历史图标（开抽屉）+「＋」新对话（从抽屉内提为一级入口）；header 为文档流元素不浮于消息流之上，抽屉本体逻辑不动（票 27 决策 9 的自绘路线不变）。
全部消费票 66 动效 token，≤400ms、transform/opacity only、`zy-` 前缀。

**第 3 段 · 弱页翻新（原票 68）**

1. profile 重构：头部账号卡升级 + 入口列表换 iconfont 图标。
2. 挂号三级列表（hospitals → campuses → departments）：步骤进度指示（复用 consult-progress 视觉语言的轻量版）+ 三页卡片差异化（医院卡突出等级/距离、院区卡突出地址、科室卡突出科类/楼层）。
3. messages / drug-orders 结构化：messages 加已读/未读视觉区分；drug-orders 订单状态 pill 化，关键信息分层。

**第 4 段 · 功能页信息分层（原票 73，最可砍）**

1. messages：服药打卡（PENDING 到点提醒）固定置顶于普通消息之上并分区。
2. report：「拍照上传」升实心主 CTA，相册/PDF 次级描边。
3. report/detail：指标卡分组——首行 指标名+值+参考区间+徽标，解释次级化，确认/纠错/排除聚合底部操作条。
4. appointments：待支付单视觉强化；可选状态筛选 tabs（前端过滤，不加接口）。
5. health：信息分层——血型/过敏重要信息固定 hero 下第一层，指标卡其次，时间线沉底。
6. standard-departments：左栏选中项加主色竖条。

**Blocked by:** 66 - 小程序视觉基线统一（已 done）

**Status:** done

- [x] 第 1 段：首页 IA 重排 + registration-card 双模式 + chat 空态精简
- [x] 第 2 段：chat 动画 + 常驻免责横条移除（每消息标注保留）+ voiceHint 内联化与卡死修复 + 气泡栏滚动条隐藏 + 页内次级 header（历史/新对话一级入口）（与票 70 边界：光标/工具进度不碰）
- [x] 第 3 段：profile / 挂号三级列表 / messages 与 drug-orders
- [x] 第 4 段：messages 服药置顶 / report 主次 CTA / detail 分组 / appointments 待支付 / health 分层 / 科室双栏竖条
- [x] 四段独立 commit；自动静态校验通过；每条 AI 产出免责声明保留
- [ ] 支付宝开发者工具/真机人工走查（按用户明确指示跳过，未操作用户电脑）
- [x] 四段均已施工，无未施工段
- [x] 票单置 done 前：README 依赖图 T67 节点加 `[x]`

## Comments

- 规格文档：`.scratch/zhiyu-mvp/spec-uiux-phase2.md`（Spec 0002，2026-08-09），问题定义/用户故事/测试与范围以该 spec 为准。
- 合并决议（2026-08-09，用户拍板）：原 67/68/72/73 四票合一。当初 67 独立是为 chat 动画的整票回退保险，现最高风险的流式光标与工具进度条已由票 70 接管（票 70 注明"替代票 67 光标条"），剩余入场动画爆炸半径小；回退颗粒度降为 commit 级，要求每段独立 commit。69（剧本扩拍）不并入——改的是 `.scratch` 演示文档非代码，验收路径不同。
- 段序依据：第 1 段首页是落地页演示第一眼；第 2 段动画砸演示主舞台；第 3/4 段按演示价值排，尾部可砍。
- grilling 终版设计原则（贯穿全票）：首屏自答（用户来这页干什么不滑屏可见）；时效压过目录（待办/待支付/到点提醒 > 浏览列表）；一个能力一处主入口；低频目录下沉。
- 实施完成（2026-08-09）：四段提交依次为 `1c5a207`（首页 IA）、`45ad091`（chat/动效）、`77c326c`（弱页翻新）、`6edf5db`（功能页分层）；`43ace23` 补齐报告/药盒入口的 AI 气泡挂载动效。
- 自动校验：`npm --prefix miniprogram ci` 成功；81 个小程序源 JS、42 个 JSON 语法检查通过；18 个变更 AXML 标签嵌套检查与 `git diff --check` 通过；未触碰 server-java、server-py、contracts 或 schema。
- 人工验收：用户明确要求不操作其电脑，支付宝开发者工具 23 页走查、控制台检查及真机语音三态验收均跳过，未宣称通过。
