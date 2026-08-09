# 76 - 购药卡片契约与 SSE 事件

**What to build:** 打通 AI 购药卡片的契约层与持久化层，使 server-py 能产出、server-java 能透传并落库两种新卡片。`contracts/sse-events.json` 的 `message_kinds` 与 `ai_card_kinds` 新增 `drug_order_confirm`（购药确认卡，待用户确认，不扣库存）与 `drug_order`（购药结果卡，已建单）；`event_to_kind` 加两个事件->kind 映射；`card_events` 加对应卡片事件名。`ContractsConsistencyTest` 同步断言：两 kind 在 message_kinds/ai_card_kinds 一致、event_to_kind 对齐、与 trace_events 不相交、不与 done 重名。server-java SSE 透传与 messages 表持久化两个 kind（drug_order_confirm 的 content 存确认卡 JSON、drug_order 存订单视图 JSON）。此票只通契约与落库，前端渲染在 77。

**Blocked by:** 74 - 药品处方属性与订单处方可空（schema 与 service 基线）

**Status:** done

- [x] contracts/sse-events.json：message_kinds + ai_card_kinds 加 drug_order_confirm、drug_order；card_events 加两事件名；event_to_kind 加映射；附 _doc 说明两 kind 语义（确认卡不扣库存/结果卡已建单、source 字段区分 otc/prescription）
- [x] ContractsConsistencyTest 同步断言新 kind 一致性、与 trace_events 不相交、不重名 done
- [x] server-java SSE 透传：两个新卡片事件能从 server-py 流经 server-java 透传到 C 端（复用现有 card_events 透传路径，不新增 SSE 机制）
- [x] server-java messages 表持久化：两 kind 能落库与回放（content 存卡片 JSON，kind 列存对应值）；历史会话回看能还原两卡片
- [x] contracts/order-flow.json 已在 74 加 source 字段；此票确认 drug_order kind 的卡片 schema 字段清单（订单号/status/total_amount/items/prescription_source?）写入契约或 _doc
- [x] server-java 单测：两 kind 事件透传不丢字段；落库后回放 kind/content 一致
- [x] README.md 依赖关系图新增节点 T76（未完成不加 [x]）
