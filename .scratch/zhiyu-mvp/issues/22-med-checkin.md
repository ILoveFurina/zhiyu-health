# 22 — 服药打卡

**What to build:** 用药提醒从 Mock 升级为服药打卡：server-java 按已审核通过处方生成到点提醒（复用站内消息），患者点“已服用”后由 server-java 幂等写入打卡记录并计算连续天数；server-py 不参与业务写入。

**Blocked by:** 21 — 健康档案

**Status:** done

- [x] server-java 按已审核通过处方的用法用量生成提醒记录，重复调度不得生成重复提醒
- [x] 打卡接口校验患者与当前档案归属；同一提醒重复点击幂等，连续天数由 server-java service 计算
- [x] 档案时间线可见打卡记录
- [x] 状态、决定与站内消息类型从 `contracts/` 推导；DTO/Entity/View 映射使用 MapStruct，新 CRUD service 继承 `ServiceImpl`
- [x] MockMvc 覆盖正常打卡、重复打卡、越权档案、未审核处方不生成提醒

## Comments

- 2026-07-29：补齐业务写入归属、幂等和跨栈边界，避免把提醒调度或打卡状态放入 server-py。
- 2026-08-03（grill-with-docs 设计澄清，未动正文）：
  - **无 Mock 现状**：经代码核查，仓库无既有"服药提醒 Mock"，`spec.md:95` 的 `med_checkin_records` 为规划实体，本票为首次实现。正文"用药提醒从 Mock 升级为服药打卡"措辞沿用自早期设计文档（`docs/to-human/...design-v2.md:1547` 在 `GET /api/c/messages` schema 里举了 `care_reminder` type，亦为前向引用未实现），实际为新建。
  - **"复用站内消息"重解读**：不复用 `in_app_messages` 表（其 `disclaimer NOT NULL`、`UNIQUE(related_appointment_id, type)`、append-only 语义与带生命周期的服药提醒不兼容），改为复用站内消息通道/UI--新建 `med_checkin_records` 表承载 `PENDING->CHECKED` 全生命周期，C 端消息页聚合 `in_app_messages ∪ med_checkin_records(PENDING, due_date<=today)` 展示。`in_app_messages` 一字不改。
  - **归属层级直接式**：`med_checkin_records` 直接存 `patient_id`+`health_profile_id`+`prescription_id`+`prescription_item_id`（参照 `report_interpretations` 先例），不挂 appointment；生成时 `health_profile_id` 经 `prescription.appointment_id -> appointments.health_profile_id` 反查一次。
  - **调度模型**：eager 预生成 + 查询时 `due_date<=today AND status=PENDING` 过滤，不起 `@Scheduled` 定时器（详见 ADR-0018）。
  - **粒度**：按天展开（`duration` 最小正则解析成天数，抓不到默认 7 天并记日志），`dosage`+`frequency` 进提醒文案不参与调度。
  - **幂等**：生成幂等 `UNIQUE(prescription_item_id, due_date)` + `ON CONFLICT DO NOTHING`；打卡幂等 `UPDATE WHERE status=PENDING` 看 affectedRows；CHECKED 不可回退。
  - **streak**：写死 `Asia/Shanghai` 取"今天"，今天已打从今天数、今天未到点从昨天数、漏一天归零；不存派生列，打卡接口现算。
  - **免责声明**：PENDING 与 CHECKED 记录都带，复用 `contracts/disclaimer.json` 统一文案，`disclaimer NOT NULL`。
  - **契约**：新建 `contracts/med-checkin-flow.json`（statuses/status_labels/decisions/message_types），新增 `Contracts.MedCheckinFlow` record；`message_types` 至少含 `medication_reminder: MEDICATION_REMINDER`。
  - **时间线**：`HealthProfileMapper.selectTimeline` 加第 4 路 UNION ALL 读 `med_checkin_records(status=CHECKED)`，type=`MED_CHECKIN`，`occurred_at=checked_at`。
  - **C 端**：消息页内每条 PENDING 带"已服用"按钮调 `POST /api/c/med-checkins/{id}/check`，不建独立打卡页。
  - **server-py 边界**：完全不参与，提醒生成/打卡/streak/时间线第 4 分支全部 server-java 直写直读。
  - CONTEXT.md 已补"站内消息通道""服药打卡"两条术语澄清。

### 2026-08-03 - 实施与验证

- 新建 `contracts/med-checkin-flow.json`（statuses/status_labels/decisions/message_types/timeline_types）+ `Contracts.MedCheckinFlow` record + ContractsTest 断言。
- schema.sql 新增 `med_checkin_records` 表（直接式 FK：patient_id+health_profile_id+prescription_id+prescription_item_id；`UNIQUE(prescription_item_id, due_date)` 生成幂等；CHECK 约束保证 CHECKED 必有 checked_at、PENDING 不得有）+ 索引。
- entity `MedCheckinRecord` + mapper `MedCheckinRecordMapper`（insertIgnore ON CONFLICT DO NOTHING、check 条件 UPDATE、selectPendingDue、selectCheckedDatesDescending、selectOwned）。
- service `MedCheckinService extends ServiceImpl`：eager 生成（duration 正则解析天/周/月，抓不到默认 7 天记日志）、打卡幂等（affectedRows 守门，CHECKED 不可回退）、streak 现算（写死 Asia/Shanghai，今天已打从今天数/未到点从昨天数/漏一天归零）、越权 404。
- controller `MedCheckinController`：`GET /api/c/med-checkins`（消息页聚合 PENDING）、`POST /api/c/med-checkins/{id}/check`（打卡）。
- `PrescriptionService.review` 审核通过分支接入 `medCheckinService.generateForApprovedPrescription`，驳回不生成。
- `HealthProfileMapper.selectTimeline` 加第 4 路 UNION ALL（type=MED_CHECKIN，summary=药名+剂量+频次，occurred_at=checked_at）。
- miniprogram 消息页聚合 `in_app_messages ∪ med_checkin_records(PENDING)`，打卡提醒带"已服用"按钮，点击后移除并 toast 连续天数。
- 测试：MedCheckinServiceTest 11 项（eager 生成/周月解析/默认天数/处方缺失/打卡/streak 三口径/越权/列表）+ MedCheckinControllerTest 2 项（列表+打卡）+ PrescriptionServiceTest 新增驳回不生成断言 + ContractsTest 新增 med-checkin-flow 断言。本票相关 37 项测试全绿。
- spotless:apply 通过；ArchUnit 通过。
- 注：`ContraindicationControllerTest` 4 项失败为 main 分支 pre-existing 失败，与本票无关（已在 main 分支单独验证）。

### 2026-08-03 - code-review 修复

- 新建 `service/mapping/MedCheckinDtoMapper.java`（MapStruct），替换 `MedCheckinService` 手写 `toView`，满足"DTO/Entity/View 映射用 MapStruct"硬约束。
- `MedCheckinControllerTest` 补 MockMvc 测试：重复打卡幂等（200 + 已服用）、越权档案 404。"未审核处方不生成提醒"属 service 层逻辑，由 `PrescriptionServiceTest.rejectionDoesNotGenerateMedCheckinReminders` 单元测试覆盖（controller 层不承载该逻辑）。
- 本票相关测试增至 39 项全绿。
