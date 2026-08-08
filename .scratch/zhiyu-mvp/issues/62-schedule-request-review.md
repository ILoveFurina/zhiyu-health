# 62 - 医生排班申请与管理员审核闭环

**What to build:** 将医生排班从 seed 写死改为「医生批量申请 -> 管理员审核 -> 落盘 schedules -> C 端可见」的闭环。医生只能为自己排班，限当天起 14 天内，支持按日期范围×多时段批量提交；管理员审核通过后复用现有 `ScheduleService.createSchedule`（含 `SlotAccounting` 的 Redis/PG 双写一致性）落盘为 `schedules` 行，患者即可挂号；驳回需填原因。seed 排班从 14 天缩短为 7 天，超出部分由审核流程产生。C 端与 server-py 零改动。

**Blocked by:** 30 - 排班号源迁移（done）；39 - 医生页挂号费（done）

**Status:** claimed

## 范围边界

- 排班来源统一走「医生申请 -> 管理员审核」流程；admin 不直接创建排班，只审核。
- 医生只能为自己排班（`doctorId` 须与登录身份一致），不支持代排。
- 可排班日期限制为当天起 14 天内（契约 `max_days_ahead`），号源上限 50（契约 `max_total_slots`）。
- 审核通过后数据落盘到既有 `schedules` 表，C 端 `selectFutureByDoctor` / Agent `selectAvailableByDoctor` 无需改动。
- seed 排班保留 7 天作为演示基线（非审核产生），保证当下仍有可挂号源。

## 数据模型

- [x] `schema.sql` 新增 `schedule_requests` 表：`doctor_id`、`schedule_date`、`time_slot`、`total_slots`、`status`（PENDING/APPROVED/REJECTED）、`submitted_by`、`reviewed_by`、`review_reason`、`schedule_id`（审核通过回填）、审计时间；在 `staff_users` 之后创建（外键引用 `staff_users(id)`）。
- [x] 同医生同日同时段允许重复提交，靠审核去重，不做唯一约束。
- [x] `seed.sql` 排班从 14 天改为 7 天（`days(day)` VALUES 从 14 个改为 7 个）。

## 契约

- [x] 新增 `contracts/schedule-request-flow.json`：`statuses`/`status_labels`/`decisions`/`time_slots`/`max_days_ahead`/`max_total_slots`/`review_reason_max_length`，双栈共享单一事实源。
- [x] `Contracts.java` 加载并类型化（`ScheduleRequestFlow` record）；`ContractsTest` 增量断言。

## server-java

- [x] `entity/ScheduleRequest` + `mapper/ScheduleRequestMapper`（`selectForReview`/`selectByDoctor`/`selectDetailedById`/`review` 条件更新）。
- [x] `service/ScheduleRequestService`：`submit`（批量提交，逐条校验日期/号源/时段）、`listMine`、`listForReview`、`review`（通过则事务内 `createSchedule` + 回填 `schedule_id`；驳回需原因；条件更新判 409）。
- [x] `controller/b/ScheduleRequestDoctorController`（`/api/b/reception/schedule-requests`，reception 豁免区，医生身份由 service `requireDoctor` 校验）。
- [x] `controller/b/ScheduleReviewController`（`/api/b/schedule-requests`，AdminInterceptor 保护）。
- [x] 复用 `ScheduleService.createSchedule`，不改 `ScheduleService`；C 端与 server-py 零改动。

## 优化（第二轮）：时段窗口 + 排班表 + 调整/停诊申请

- [x] 契约 `schedule-request-flow.json` 去掉晚上时段（只保留上午/下午），新增 `time_slot_windows`（上午 09:00-11:30，下午 14:00-18:00）、`actions`（CREATE/MODIFY/DISABLE）、`action_labels`。
- [x] `schema.sql` `schedule_requests` 表新增 `action`（默认 CREATE）和 `target_schedule_id`（MODIFY/DISABLE 指向被操作的 schedules 行）列。
- [x] `AppointmentService.reserve()` 新增时段截止校验：排班当天当前时间超过时段结束时间则拒绝挂号（409）。
- [x] `ScheduleRequestService` 新增 `submitChange`（MODIFY/DISABLE）和 `listMySchedule`；`review` 按 action 类型分别调用 `createSchedule`/`updateSchedule`/`disableSchedule`。
- [x] `ScheduleRequestDoctorController` 新增 `GET /api/b/reception/schedule-table` 和 `POST /api/b/reception/schedules/{id}/change-request`。
- [x] admin 新增 `pages/ScheduleTable`（排班表页，展示未来排班 + 调整号源/停诊操作）；排班申请页改为可编辑表格（逐条设号源数），去掉晚上时段。
- [x] 路由菜单：医生菜单 `业务管理` 下 接诊台 -> 排班表 -> 排班申请；排班审核页新增操作类型列。
- [x] 测试：`ScheduleRequestServiceTest` 新增 submitChange/listMySchedule/review approve modify/disable；`ContractsTest` 断言 actions/time_slot_windows；端到端冒烟全通过。
- [ ] 浏览器实测无控制台错误：医生登录 -> 排班表查看 -> 调整号源/停诊申请 -> 排班申请页可编辑表格 -> admin 排班审核页通过/驳回 -> 全程无红色控制台错误。

## admin 前端

- [x] `pages/ScheduleRequest/index.tsx`：医生批量排班申请页（日期范围 + 时段多选 + 号源数，笛卡尔积展开）+ 申请列表。
- [x] `pages/ScheduleReview/index.tsx`：管理员审核页（待审核列表 + 通过/驳回 Modal + 409 幂等 + reviewingId 锁），仿 `Prescription/index.tsx`。
- [x] `services/scheduleRequest.ts` + `contracts/scheduleRequest.ts`。
- [x] `routes.ts` 新增 `/schedule-request`（无 access 限制）与 `/schedule-review`（`canAdmin`）；`app.tsx` 菜单分组与 `ADMIN_PATHS` 同步。

## 测试与验证

- [x] `ScheduleRequestServiceTest`（service 级单测）：批量提交校验（超 14 天/号源越界/非医生/doctorId 不匹配/空 items）、审核通过落盘 + schedule_id 回填、驳回无原因 400、并发审核 409、404、列表。
- [x] `ScheduleRequestControllerTest`（MockMvc 冒烟）：提交 + 审核 + 角色门禁 + 400。
- [x] `ContractsTest` 增量断言 `schedule-request-flow.json`。
- [x] `verify_zhiyu.py` 更新 schedules 行数（420->210）+ schedule_requests 表存在性断言。
- [x] 端到端冒烟：医生提交 -> admin 审核通过 -> schedules 落盘 -> admin 驳回 -> 并发审核 409 -> 超 14 天被拒。
- [ ] 浏览器实测无控制台错误：医生登录 -> 排班申请页提交 -> admin 登录 -> 排班审核页通过/驳回 -> 全程无红色控制台错误。
- [ ] 票单置 `done` 前确认 `README.md` T62 节点改为 `[x]62`。

## Comments

- 2026-08-08：批量排班 + admin 仅审核（不直接排班）为用户确认的方案选择。
