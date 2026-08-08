# 65 - 智能导诊多科室选择卡 + 引导卡去重

**What to build:** 两项用户反馈同一票施工（grilling 已收敛五个决策点）：

1. **ambiguous 出科室选择卡**：现状契约定义 ambiguous「只追问或让用户选择，不查询」，但「让用户选择」端侧无 UI，多科室推荐时无任何可操作出口。本票落地：triage judge 的 `TriageResolution` 扩展 `candidate_department_ids`（ambiguous 时输出，取自候选列表、越界丢弃、去重、上限取契约 `options_max_candidates`=3）；编排层在判 ambiguous 且候选非空时，Agent 文字流照常（message 事件后）追加 `department_options` 选择卡事件再 done，不短路、不查号源。契约 `guided-registration.json` 增 `options_card_event`/`options_max_candidates`/`options_select_user_text`，ambiguous 语义与 retry 字段语义（泛化为「已确定科室直查」）文档同步；`sse-events.json` 四清单（card_events/message_kinds/ai_card_kinds/event_to_kind）登记新事件。
2. **点选直查**：小程序渲染选择卡（科室 chips），点选 → `startRound` 携带 `retry_standard_department_id`（复用票 50 直查通道，跳过解析与 Agent 流）出科室号源卡；用户消息文案镜像契约 `options_select_user_text`。
3. **持久化回放**：`department_options` 进 `ai_card_kinds` 后 server-java `persistEvent` 通用分支自动落库；`schema.sql` 的 `ck_messages_kind` CHECK 两处扩列；小程序 `CARD_KINDS` + drawer 回放分支（disclaimer 取卡片 JSON 优先）。不追踪已选状态，可重复点（只读查询幂等，与重试卡行为一致）。
4. **引导卡去重**：`enterTriage()` 现状每点必插一张 `feature_guide`，与 CONTEXT.md「插入一张」词条矛盾。改为插入前过滤已有 feature_guide 再底部插入（全程一张、始终在当前视线），一处管住气泡/首页交棒/空态三入口。

**Blocked by:** 无（基于票 50/62 已合入的导诊链路）

**Status:** claimed

- [x] 契约：guided-registration.json 扩三键 + 文档更新；sse-events.json 四清单登记 department_options
- [x] server-py：TriageResolution/judge prompt/_normalize 候选输出；chat.py 编排追加选择卡；runner CardEvent Literal；contracts.py 模型扩字段
- [x] server-py 测试：ambiguous 出卡/无候选不出卡、_normalize 过滤截断、契约消费钉值
- [x] server-java：Contracts.GuidedRegistration 扩字段、schema.sql CHECK 扩列、ContractsTest/ContractsConsistencyTest 钉值同步
- [x] 小程序：chat-stream dispatch、index.js 处理器与点选、index.axml/acss 选择卡、message-kinds、drawer 回放、feature-guide 去重
- [x] CONTEXT.md「智能导诊」词条更新（选择卡具象化 + 引导卡一张）+ 新增「科室选择卡」词条
- [x] `uv run pytest`（191 过；test_knowledge_integration 2 项失败为向量未回填的既有环境问题，与本票无关）+ ruff + mypy + lint-imports 绿；server-java ContractsTest/ContractsConsistencyTest 39 项绿 + spotless 绿
- [x] schema 变更后 `reset_zhiyu.py` 重建 + 重启 server-java + `verify_zhiyu.py` 验证通过
- [ ] 开发者工具实测：多科室出选择卡 / 点选出号源卡 / 回放选择卡 / 气泡不再连弹
- [ ] 票单置 done 前：README 依赖图 T65 节点加 `[x]`（立项先加节点）

## Comments

- 施工记录（t65-triage-department-options 分支，原编号 64 与排班审核票撞号后改 65）：选择卡事件序列钉死为 meta → token×N → message → department_options → done；judge 候选 id 在 `_normalize` 过滤越界/保序去重/截断契约上限 3，编排层只做 id→name 确定性映射。server-java 侧零透传改动：`persistEvent` 的 `isAiCardKind` 通用分支自动落库，唯一 DB 面变更是 `ck_messages_kind` 扩列（已重建验证）。选择卡回放无需加工，drawer 仅补 disclaimer 优先取卡片 JSON 的分支。
