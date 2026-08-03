# 服药打卡调度模型：eager 预生成 + 到点过滤

Status: accepted

票 22（服药打卡）需要"按已审核通过处方的用法用量生成到点提醒，重复调度不得生成重复提醒"。处方审核通过（`PENDING -> APPROVED`）那一刻，server-java 把整段疗程展开成每日一条 `med_checkin_records` 记录（status=`PENDING`，带 `due_date`），写入即结束，无后台线程。C 端消息页与打卡接口查询时用 `due_date <= today AND status = PENDING` 过滤可见提醒；患者点"已服用"后状态机推进为 `CHECKED` 并落 `checked_at`，记录离开消息通道、进入健康档案时间线第 4 分支。生成幂等由 `UNIQUE(prescription_item_id, due_date) ON CONFLICT DO NOTHING` 兜底，打卡幂等由 `UPDATE ... WHERE status = PENDING` 看 `affectedRows` 兜底，CHECKED 不可回退。

决策点：

- **按天而非按次展开**。`prescription_items.dosage/frequency/duration` 均为医生手填自由 `VARCHAR`，无结构化时刻语义；按次展开需先造一个"每日3次/每8小时/睡前"解析器再为它兜底，对两周 demo 是过度工程。按天展开让 streak（连续天数）有无歧义定义（每天至多一打卡），eager 生成量小（7 天 = 7 行），`dosage`+`frequency` 退化进提醒文案保留信息但不参与调度。`duration` 用最小正则（数字+天/周/月）解析成天数，抓不到默认 7 天并记日志。
- **不复用 `in_app_messages` 表，只复用站内消息通道/UI**。`in_app_messages` 是一次性事件表（就诊小结等），`disclaimer NOT NULL`、`UNIQUE(related_appointment_id, type)`、append-only；服药提醒是带生命周期的调度记录（PENDING->CHECKED、到点才可见、打卡后离开通道），塞进去要加 `due_at`/`status`/`related_prescription_id` 并放宽唯一约束，把干净的事件表污染成调度表。新建 `med_checkin_records` 承载全生命周期，`in_app_messages` 一字不动，C 端消息页聚合两者展示。
- **归属层级用直接式 FK**。`med_checkin_records` 直接存 `patient_id`+`health_profile_id`（参照 `report_interpretations` 先例），不挂 `appointment_id`；生成时 `health_profile_id` 经 `prescription.appointment_id -> appointments.health_profile_id` 反查一次。这让时间线第 4 分支与 REPORT_INTERPRETATION 分支同构、零额外 join。

## Consequences

- **无定时器依赖**：本地三服务 + 云数据拓扑下不起 `@Scheduled`，无长驻线程、无重启状态恢复问题；"到点"完全由查询时 `due_date <= today` 计算。
- **预生成可回看**：PENDING 记录持久化，时间线能回看"哪天漏服"（漏服即 PENDING 长期未推进），故事性强、demo 友好。
- **生成幂等下沉到 DB**：`UNIQUE(prescription_item_id, due_date)` + `ON CONFLICT DO NOTHING` 让"重复审核/重投/将来加重新生成按钮"都静默吞掉，无需应用层 select-then-insert。
- **打卡幂等靠状态机**：`UPDATE WHERE status=PENDING` 一句话双关"首次打卡"与"重复点击"，CHECKED 不可回退保证时间线稳定；代价是误打无法撤回（demo 可接受，票面未提取消）。
- **streak 不落库**：连续天数由打卡接口现算（写死 `Asia/Shanghai` 取今天，今天已打从今天数、未到点从昨天数、漏一天归零），无派生列一致性负担；代价是每次查 O(天数)，demo 疗程几天可忽略。
- **生成量可控**：`每日3次 × 7天` 按次会产 21 行，按天只产 7 行；demo 处方疗程都是几天，eager 生成量可接受。若将来疗程长达月级，按天展开的行数仍线性，无需改架构。
- **server-py 边界不变**：提醒生成、打卡、streak、时间线第 4 分支全部 server-java 直写直读，Agent 层不暴露任何打卡工具，符合"业务写入只在 server-java"。
- **契约新增**：新建 `contracts/med-checkin-flow.json`（statuses/status_labels/decisions/message_types，`message_types` 至少含 `medication_reminder: MEDICATION_REMINDER`），新增 `Contracts.MedCheckinFlow` record 与 `read(...)` 行；状态、决定与站内消息类型仍从 `contracts/` 推导，符合硬约束。

## 被否决的方案

- **`@Scheduled` 后台轮询**：起定时任务到点扫库把 PENDING"激活"成可见消息。需要长驻线程，与"本地三服务 + 云数据"的 demo 拓扑和两周交付节奏不匹配；重启状态恢复、轮询间隔与"到点"精度的取舍都是纯负担。eager 预生成 + 查询时过滤用零后台线程达到同等效果。
- **Lazy 按需计算（不预生成）**：每次查消息时现算"现在该提醒哪些"，靠已打卡记录反查。无持久提醒实体，时间线无法回看漏服，"重复调度不得生成重复提醒"失去落点。违背票面"档案时间线可见打卡记录"。
- **按次展开**：把 `frequency` 自由文本解析成精确时刻表（08:00/14:00/20:00）。`frequency` 是 `VARCHAR(100)` 手填值，"每日3次/每8小时/睡前/必要时"都得有规则且要为解析失败兜底，复杂度与 demo 价值不匹配；按天展开后 streak 定义更直白。
- **全塞 `in_app_messages`**：加 `status`/`due_at`/`related_prescription_id` 并放宽 `UNIQUE(related_appointment_id, type)`。破坏该表 append-only 事件语义与现有就诊小结幂等键，把两张表的职责混进一张表，违反"一个文件只承担一个职责"（硬约束 #7）。
- **streak 落 `current_streak` 派生列**：打卡时增量更新。但"漏服归零"要靠定时或查询时校验"最后打卡是不是今天/昨天"，否则数据会撒谎（存了 7 但昨天漏了），又回到要不要定时器的问题，与"无后台线程"决策冲突。现算无一致性问题。
