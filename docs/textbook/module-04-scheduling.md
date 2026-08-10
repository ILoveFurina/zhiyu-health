# 模块4：排班与调班（B 端）

## 业务概述

排班是连接 B 端医生工作台与 C 端挂号入口的枢纽：医生在 B 端批量提交排班申请（含新增排班、调整号源、停诊、恢复出诊四种操作），管理员审核通过后申请才落盘为 `schedules` 行，C 端小程序患者方可挂号。本模块是一个典型的**状态机驱动的审批流**——申请有 `PENDING → APPROVED / REJECTED` 三个状态，同时排班行又与模块3的号源体系深度联动：审核通过的落盘、号源容量调整都必须经过 `SlotAccounting` 保证 Redis 计数与 PostgreSQL 的双写一致性。

## 业务流程

1. 医生登录 B 端（admin），进入「排班申请」页，选择日期范围（限当天起 14 天内，上限来自契约 `max_days_ahead`）与时段（上午/下午），前端展开为「日期 × 时段」明细，逐条填号源数后批量提交。
2. server-java `ScheduleRequestDoctorController` 接收请求，service 层校验：登录者必须是医生角色且 `doctorId` 与本人一致（只能为自己排班）、日期/时段/号源数合法、同日同时段无重复排班或待审核申请；全部通过则逐条写入 `schedule_requests` 表，状态 `PENDING`。
3. 医生也可在「排班表」页对已有排班发起**调班**：调整号源（MODIFY）、停诊（DISABLE）、恢复出诊（ENABLE，与停诊互为逆操作），同样生成一条 `PENDING` 申请，`target_schedule_id` 指向被操作的排班行。
4. 管理员进入「排班审核」页查看待审核列表，点击「通过」或「驳回」（驳回必填原因，上限 500 字）。
5. 审核通过时，server-java 按 action 类型落盘：CREATE 新建 `schedules` 行（初始化号源 Redis 计数）、MODIFY 调整号源容量（Redis 增量对账）、DISABLE 停诊、ENABLE 恢复出诊；随后用**条件更新**（`WHERE status='PENDING'`）把申请置为 `APPROVED`，并发审核只有一个决定生效，后到者收到 409。
6. 排班行一旦 `is_active=true` 且存在，C 端挂号列表即可见（模块3），患者挂号时经 `SlotAccounting` 原子扣减号源；停诊或有待审核停诊申请的排班在 C 端直接不展示。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| 契约 | 状态/决定/操作类型/时段/上限的单一事实源 | `contracts/schedule-request-flow.json` |
| admin 契约适配 | 从契约 JSON 导出 TS 常量供前端使用 | `admin/src/contracts/scheduleRequest.ts` |
| admin 页面 | 医生排班申请表单（日期×时段批量提交） | `admin/src/pages/ScheduleRequest/index.tsx` |
| admin 页面 | 医生排班表（未来排班 + 调班/停诊/恢复入口） | `admin/src/pages/ScheduleTable/index.tsx` |
| admin 页面 | 管理员审核列表（通过/驳回） | `admin/src/pages/ScheduleReview/index.tsx` |
| admin 服务 | 排班申请/审核 API 封装 | `admin/src/services/scheduleRequest.ts` |
| server-java controller | 管理员直连排班 CRUD（`/api/b/schedules`） | `server-java/src/main/java/com/zhiyu/health/controller/staff/scheduling/ScheduleController.java` |
| server-java controller | 医生排班申请入口（`/api/b/reception/**`，医生可达） | `server-java/src/main/java/com/zhiyu/health/controller/staff/scheduling/ScheduleRequestDoctorController.java` |
| server-java controller | 管理员审核入口（`/api/b/schedule-requests/**`） | `server-java/src/main/java/com/zhiyu/health/controller/staff/scheduling/ScheduleReviewController.java` |
| server-java service | 审批闭环：提交校验、审核落盘、并发保护 | `server-java/src/main/java/com/zhiyu/health/service/scheduling/ScheduleRequestService.java` |
| server-java service | 排班 CRUD 与号源双写一致性 | `server-java/src/main/java/com/zhiyu/health/service/scheduling/ScheduleService.java` |
| server-java service | 号源 Redis/PG 补偿收口（模块3共用） | `server-java/src/main/java/com/zhiyu/health/service/scheduling/SlotAccounting.java` |
| server-java mapper | 申请条件更新、排班查询（含 pending_action 投影） | `server-java/src/main/java/com/zhiyu/health/mapper/scheduling/ScheduleRequestMapper.java`、`ScheduleMapper.java` |

## 核心代码走读

### 4.1 契约驱动：状态、操作类型与上限都来自 JSON

`contracts/schedule-request-flow.json:2-39` 定义了整个审批流的词汇表——状态机、审核决定、四种操作类型、时段窗口与可排班天数上限：

```json
  "statuses": { "pending": "PENDING", "approved": "APPROVED", "rejected": "REJECTED" },
  "decisions": { "approve": "APPROVE", "reject": "REJECT" },
  "actions": { "create": "CREATE", "modify": "MODIFY", "disable": "DISABLE", "enable": "ENABLE" },
  "time_slot_windows": {
    "上午": { "start": "09:00", "end": "11:30" },
    "下午": { "start": "14:00", "end": "18:00" }
  },
  "max_days_ahead": 14,
  "max_total_slots": 50,
```

server-java 通过 `Contracts.scheduleRequestFlow()` 读取（`ScheduleRequestService.java:276-286` 的 `status()/decision()/action()` 私有方法全部是查契约），admin 侧则经 `admin/src/contracts/scheduleRequest.ts:1-12` 直接 import 同一个 JSON 导出 TS 常量。这样状态机只有一个事实源：Java 里不会出现硬编码的 `"PENDING"` 字符串，前端的状态标签、时段选项、日期上限也全部从契约推导，改契约即双栈同步。

### 4.2 医生提交：越权防护与批量校验

`ScheduleRequestService.submit()`（`ScheduleRequestService.java:36-76`）是医生批量新增排班的入口，先看开头的身份与越权防护：

```java
    public List<ScheduleRequest> submit(long staffId, long doctorId, List<ScheduleRequestItem> items) {
        long staffDoctorId = requireDoctor(staffId);
        if (staffDoctorId != doctorId) {
            // 医生只能为自己排班，doctorId 不匹配拒绝，防止越权代排。
            throw new ApiException(403, "只能为自己提交排班申请");
        }
        if (items == null || items.isEmpty()) {
            throw new ApiException(400, "排班申请不能为空");
        }
```

注意医生申请接口挂在 `/api/b/reception/**`（`ScheduleRequestDoctorController.java:33-36`），该路径是 `AdminInterceptor` 的豁免区，因此**角色校验下沉到 service 层**：`requireDoctor()`（`ScheduleRequestService.java:268-274`）查 `staff_users` 确认角色为 doctor 且绑定了 `doctorId`，再与请求体里的 `doctorId` 比对。这是「controller 只做装配、鉴权规则由 service 承载」的典型写法。随后每条申请校验日期窗口、时段、号源上限，并做查重（`checkDuplicateCreate`，`ScheduleRequestService.java:241-248`）：同医生同日同时段已有活跃排班或待审核申请都拒绝，避免审核通过后落出重复排班行。

调班入口 `submitChange()`（`ScheduleRequestService.java:82-123`）则围绕 `target_schedule_id` 做归属与互逆校验：DISABLE 只能对可出诊排班发起、ENABLE 只能对已停诊排班发起、只能调整自己的排班、只能调整未来日期的排班。

### 4.3 状态机核心：审核的条件更新与并发安全

`review()`（`ScheduleRequestService.java:151-183`）是审批流的状态迁移点：

```java
        String trimmedReason = trimToNull(reason);
        String reviewTarget = target;
        Long reviewScheduleId = scheduleId;
        return transactionTemplate.execute(tx -> {
            // 条件更新保证并发审核只有一个决定生效，避免先通过后被另一请求覆盖为驳回。
            if (baseMapper.review(id, reviewTarget, trimmedReason, reviewerId, reviewScheduleId, status("pending"))
                    != 1) {
                throw new ApiException(409, "排班申请已审核");
            }
            return baseMapper.selectDetailedById(id);
        });
```

状态迁移不是「先查状态再更新」，而是一条带条件的 UPDATE（`ScheduleRequestMapper.java:64-66`）：

```java
            UPDATE schedule_requests SET status = #{status}, reviewed_by = #{reviewerId},
              review_reason = #{reason}, schedule_id = #{scheduleId}, reviewed_at = now()
            WHERE id = #{id} AND status = #{expectedStatus}
```

`WHERE status='PENDING'` 让数据库做仲裁：两个管理员同时审核同一申请，只有一个 UPDATE 命中 1 行，另一个返回 0 行被翻译成 409。这与号源扣减「禁止先查后改」是同一条纪律。入口侧的防御也值得一提：`review()` 开头还有一次 `status != PENDING → 409` 的快照检查（`ScheduleRequestService.java:156-158`），但那只是快速失败，真正的并发裁决在条件更新上。

### 4.4 审核通过落盘：按 action 分派到排班操作

审核通过不是只改状态，而是要**产生业务副作用**——`applyApprovedAction()`（`ScheduleRequestService.java:192-212`）按操作类型分派：

```java
    private Long applyApprovedAction(ScheduleRequest request) {
        if (action("create").equals(request.getAction())) {
            Schedule created = scheduleService.createSchedule(buildSchedule(request));
            return created.getId();
        }
        if (action("modify").equals(request.getAction())) {
            Schedule changes = buildSchedule(request);
            changes.setId(request.getTargetScheduleId());
            scheduleService.updateSchedule(changes);
            return request.getTargetScheduleId();
        }
        if (action("disable").equals(request.getAction())) {
            scheduleService.disableSchedule(request.getTargetScheduleId());
            return request.getTargetScheduleId();
        }
```

四种 action 全部**复用** `ScheduleService` 的既有方法，而不是在审批 service 里重写一份排班逻辑——CREATE 走 `createSchedule`（含 Redis 计数初始化）、MODIFY 走 `updateSchedule`（容量调整对账）、DISABLE/ENABLE 只翻转 `is_active`、不触碰 `remaining_slots`（停诊期间号源冻结，恢复后原值生效）。返回值 `schedule_id` 会回填到申请行，形成「申请 → 排班」的审计链路。

### 4.5 与号源的联动：SlotAccounting 双写一致性

排班落盘的第一站是 `ScheduleService.createSchedule()`（`ScheduleService.java:35-47`）：

```java
    public Schedule createSchedule(Schedule schedule) {
        if (doctorMapper.selectById(schedule.getDoctorId()) == null) {
            throw new ApiException(404, "医生不存在");
        }
        schedule.setRemainingSlots(schedule.getTotalSlots());
        schedule.setIsActive(true);
        // withInitialization 的补偿范围覆盖整个事务（含提交失败）：已初始化未提交即删除 Redis 计数。
        return slotAccounting.withInitialization(init -> transactionTemplate.execute(status -> {
            baseMapper.insert(schedule);
            init.init(schedule.getId(), schedule.getRemainingSlots());
            return schedule;
        }));
    }
```

这里体现了模块3号源体系（ADR-0007/0011）向排班的延伸：Redis 不参与 PG 事务，所以「PG 插排班行 + Redis 初始化计数」必须包进 `SlotAccounting.withInitialization`——若 PG 事务回滚或提交失败，句柄会删除已初始化的 Redis 计数，不留下「可预约的孤儿号源池」。MODIFY 的容量调整同理走 `withAdjustment`（`ScheduleService.java:49-73`）：事务内先 `selectByIdForUpdate` 加行锁算出容量增量，PG 调整成功后才对 Redis 做 `INCRBY delta`（增量与并发预约的 DECR 可交换，不会用旧快照覆盖），失败则按已应用增量反向补偿；新容量小于已用号源时直接 409。患者挂号侧的 `tryDecrementSlot`（`ScheduleService.java:97-102`）走的也是同一个 `SlotAccounting`——排班（供给）与挂号（消费）共享一套号源记账纪律。

### 4.6 前端：契约约束的表单与 409 幂等处理

医生侧 `ScheduleRequest/index.tsx:60-65` 的日期选择器直接用契约上限禁用超范围日期，与 server-java 的校验同源：

```tsx
  // 日期范围限制：只能选今天起 max_days_ahead 天内
  const disabledDate = (current: Dayjs) => {
    if (!current) return false;
    const today = dayjs().startOf('day');
    const max = today.add(scheduleRequestMaxDaysAhead, 'day');
    return current.isBefore(today) || current.isAfter(max);
  };
```

管理员侧 `ScheduleReview/index.tsx:50-57` 对 409 的处理值得单独讲——并发审核冲突不被视为失败，而是提示后刷新列表保持界面一致：

```tsx
    } catch (err: any) {
      // 幂等冲突（该申请已被审核）：另一处已处理或重复点击所致，不视为失败，刷新保持界面一致
      if (err?.response?.status === 409) {
        message.info('该排班申请已审核，请勿重复操作');
        setRejecting(undefined);
        setReason('');
        await load();
      }
```

排班表页面则展示了另一个契约复用点：`ScheduleTable/index.tsx:89-95` 的 `isSlotExpired` 用 `time_slot_windows` 判断「当天且已过时段结束时间」，与 server-java 挂号截止校验（`isSlotWindowClosed`）口径一致——同一时段窗口定义同时服务于 B 端展示与 C 端挂号截止。

## 契约与 ADR

- `contracts/schedule-request-flow.json`：本模块的单一事实源，定义状态机（PENDING/APPROVED/REJECTED）、审核决定、四种操作类型、时段窗口、`max_days_ahead=14`、`max_total_slots=50`。
- ADR-0007《号源模型：池计数，不建逐号实体》：排班行携带 `total_slots`/`remaining_slots`，是号源池的供给端；挂号扣减 = Redis 原子 DECR + PG 事务对账。
- ADR-0010《跨栈契约：contracts/ JSON 单一事实源 + 双栈启动加载》（`docs/adr/0010-cross-stack-contracts.md`；注意与另一个 0010《RAG 知识检索只用于受控证据问答与技术演示》区分）：解释状态值为什么只从 contracts/ 加载。
- ADR-0011《SlotAccounting：号源补偿收敛为命令式句柄》：排班初始化（`withInitialization`）与容量调整（`withAdjustment`）的补偿语义来源，`SlotAccounting` 是唯一操作 `SlotCounter` 的组件（ArchUnit 强制）。

## 讲解提示

- **强调状态机的「数据库仲裁」**：学生常问「为什么开头已经查了 status 不是 PENDING，后面 UPDATE 还要带 WHERE 条件？」——开头那次是快速失败的快照检查，可能读到过期数据；`WHERE status='PENDING'` 的条件更新才是并发下唯一可信的裁决，与号源「禁止先查后改」同一条纪律。可以现场起两个并发审核请求演示 409。
- **「审核通过」不是状态翻转而是业务事务**：APPROVED 的语义包含落盘副作用（建排班/调容量/停诊），且复用 `ScheduleService` 既有方法而非重写。可追问学生：如果 CREATE 落盘成功但随后的条件更新返回 0 行会怎样？——注意 `applyApprovedAction`（`ScheduleRequestService.java:163`）在 `transactionTemplate.execute` **之前**执行，`createSchedule`/`updateSchedule` 各自管理自己的事务并已提交，因此落盘不会随 409 回滚，极端并发下会留下一行已落盘但申请被 409 的排班。demo 靠 `checkDuplicateCreate` 查重与前端防重（`reviewingId` 锁操作列）把窗口压到极小；这是讨论「审批副作用与状态迁移应否同事务」的好素材。
- **排班与号源是一个闭环**：排班是号源的供给（`withInitialization` 建池、`withAdjustment` 调容量），挂号是消费（`tryDeduct`），两端都收口在 `SlotAccounting`。结合 ADR-0011 讲「Redis 不参与 PG 事务，所以每个双写序列都要带补偿」。
- **常见问题「医生接口为什么不被 AdminInterceptor 拦？」**：医生申请路径在 `/api/b/reception/**` 豁免区，角色校验由 service 层 `requireDoctor` 兜底；这也解释了为什么 `submit` 要同时传 `staffId`（登录身份）和 `doctorId`（请求体）并比对——豁免区的越权防护必须自己写。

> 返回目录：[docs/textbook/README.md](./README.md)
