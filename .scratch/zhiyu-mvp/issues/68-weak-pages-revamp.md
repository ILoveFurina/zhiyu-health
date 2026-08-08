# 68 - 小程序弱页翻新（profile / 挂号三级列表 / messages 与 drug-orders）

**What to build:** 探查定档"最糙"页面的深度翻新，纯 axml/acss 不动业务逻辑，消费票 66 的 token/图标/空态组件。**按序施工、尾部可砍**：时间不够时未完成段注明口径后票仍可置 done（票 27 先例）。

1. **profile 重构**（全场最素）：头部账号卡视觉升级（真实头像位/昵称/档案状态）+ 入口列表换 iconfont 真实图标，消费空态与按压态。
2. **挂号三级列表差异化 + 进度指示**（hospitals → campuses → departments）：三页目前复制粘贴式同构、用户不知身处第几步。加步骤指示（医院/院区/科室三段，复用 consult-progress 的视觉语言但轻量化）；各页卡片差异化——医院卡突出等级与距离、院区卡突出地址、科室卡突出科类与楼层。
3. **messages / drug-orders 结构化**：messages 加已读/未读视觉区分、就诊指引卡排版收紧；drug-orders 订单状态（UNPAID/PAID/DONE/CANCELLED）加状态 pill 与关键信息层级，替代纯文字堆叠。

**Blocked by:** 66 - 小程序视觉基线统一

**Status:** retired（2026-08-09 并入票 67「C 端 UI/UX 二期」第 3 段，内容以 67 为准）

- [ ] profile 重构
- [ ] 挂号三级列表：进度指示 + 三页卡片差异化
- [ ] messages 已读态 + drug-orders 状态结构化
- [ ] 每段完成即开发者工具实测；未做段在 Comments 注明口径
- [ ] 票单置 done 前：README 依赖图 T68 节点加 `[x]`

## Comments

- grilling 决议（2026-08-08）：三段按演示价值排序。demo 剧本主线不经过自助挂号三级列表（走 Agent 导诊出卡路径），但用户明确"全功能都要演示"，故全量纳入；若时间盒紧张，第 3 段可整段砍——messages/drug-orders 已经票 66 基线托底（杂色收编 + 空态 + 按压态），不致素到刺眼。
