# 94 - 接诊台三项增强：过点就诊中自动转已接诊 + 接诊详情补健康档案与处方明细

**What to build:** B 端接诊台三处增强：
1. **过点"就诊中"自动转"已接诊"**：医生叫号后患者进入就诊中，但医生忘了点"接诊完成"按钮，过了号源时段（甚至跨天）后该挂号单一直滞留就诊中，卡住单叫号约束（医生无法叫下一个号）。新增惰性收敛：过点（含跨天滞留）的 IN_PROGRESS 单由系统自动推进为 VISITED。只推进状态，不落接诊记录、不发就诊小结消息（医生未填诊断，不伪造医疗内容；consultation_records.diagnosis/advice NOT NULL）。复用 ADR-0038 惰性收敛范式。
2. **接诊详情补充健康档案信息**：医生点"接诊"打开抽屉，患者信息只有姓名。补性别、年龄（从出生日期算）、过敏史（空填"未填"），数据取自挂号时固化的 health_profile_id（非当前活跃档案）。
3. **接诊完成"查看"页补充信息**：已接诊查看页信息太少。补患者健康档案信息（性别/年龄/过敏史）+ 所开处方明细（药品列表：药名/规格/用法/频次/疗程/数量 + 处方状态 + 驳回原因）。

**Blocked by:** 92（收敛范式）、87（叫号时段窗口/单叫号约束）、93（已接诊查看只读）

**Status:** claimed

## 决策（用户确认）

- 问题1：只推状态 IN_PROGRESS->VISITED，不落 ConsultationRecord、不发就诊小结消息、不释放号源（号源已扣、患者已就诊）。医生未填诊断，不伪造医疗内容。
- 问题1 收敛范围：处理跨天滞留（schedule_date < today 或当天过 end）。
- 问题2/3：健康档案信息取自挂号时固化的 health_profile_id（患者可能切换档案，接诊台看挂号时锁定的档案）。
- 过敏史空填"未填"（用户要求；在线问诊抽屉用"无"，线下按用户要求用"未填"）。

## 契约与数据模型

- [x] contracts/appointment-flow.json：新增 `auto_complete_overdue` transition（from=["IN_PROGRESS"], to="VISITED"）+ _doc
- [x] Contracts.java 零改（transition 纯 JSON 加载）
- [x] schema 零变更（VISITED 已存在）

## server-java 业务后端

- [x] SlotWindowGuard：新增 `isPast(scheduleDate, timeSlotValue)`——schedule_date < today 返回 true（跨天滞留）；当天复用 isClosed；未来/null 返回 false
- [x] AppointmentMapper：新增 `selectInProgress(inProgressStatus)` 查所有 IN_PROGRESS 单（不限当天，跨天也要收敛），JOIN schedules 取 schedule_date/time_slot，投影 InProgressAppointment(id, scheduleId, patientId, scheduleDate, timeSlot)
- [x] AppointmentMapper：新增 `markVisitedIfInProgress(appointmentId, inProgressStatus, visitedStatus)` CAS（WHERE status=IN_PROGRESS，SQL 同 markVisited 但语义独立）
- [x] AppointmentService：新增 `expireUnfinishedConsultations()` + `autoCompleteOverdue(id)`（事务内 markVisitedIfInProgress CAS，不落记录/不发消息/不释放号源）
- [x] 触发点：ReceptionService.today()、AppointmentService.listForPatient()/cancel()，与 expireUncalledAppointments 并排
- [x] ReceptionMapper.selectAppointment：SELECT 加 hp.gender, hp.birth_date
- [x] Appointment 实体：加 @TableField(exist=false) gender, birthDate
- [x] ReceptionService：注入 HealthProfileAllergyMapper + PrescriptionItemMapper + Clock；detail 联查过敏史+算年龄+处方明细
- [x] AppointmentDetail record：新增 PatientProfile(gender, age, allergies) + PrescriptionDetail(status, reviewReason, items) + PrescriptionItemView

## 前端

- [x] reception.ts：AppointmentDetail 扩展 patient_profile + prescription 类型
- [x] ConsultationDrawer：顶部 Descriptions 加性别/年龄/过敏史（空填"未填"）；接诊记录区处方审核项扩展为药品列表+状态+驳回原因

## 验收与文档

- [x] AppointmentServiceTest：过点收敛、未过点不收敛、VISITED CAS 跳过、跨天滞留收敛、当天未知时段 fail-open、重复幂等
- [x] ReceptionServiceTest：detail 返回健康档案+处方明细；today 触发 expireUnfinishedConsultations
- [x] ContractsTest：auto_complete_overdue transition 断言
- [x] ADR-0039 + 修订 CONTEXT.md（挂号单/接诊/叫号）+ 修订 ADR-0034 第4条
- [x] mvn test + spotless:check；admin typecheck/build
- [ ] 前端实测：过点演示加速（/api/b/demo/time-slot-windows）；接诊详情看健康档案；查看页看处方明细
- [ ] 票单置 done 前：README 依赖图 T94 节点加 [x]

## Comments

- 问题1 与 ADR-0038（过点未叫号 BOOKED->CANCELLED+退款）平行，但目标状态是 VISITED（已就诊，不退款、不释放号源）。两者 CAS 守卫互不冲突（一个 from=BOOKED，一个 from=IN_PROGRESS）。
- 不落 ConsultationRecord 的理由：diagnosis/advice NOT NULL，且医生未填诊断不应伪造。自动转的单查看时诊断/医嘱为空，前端显示"未填写"。若医生需补诊断为后续需求（VISITED 终态不可补）。
- 跨天滞留必收敛：selectInProgress 不限当天；isPast 对 schedule_date < today 直接返回 true（不论时段是否已知），彻底清理卡住单叫号约束的滞留单。
- 顺带修复 main 分支预存 bug：ChatWebSocketHandlerTest / ChatWebSocketMessageAuthTest 的 package 声明 `controller.patient.chat` 与物理路径 `controller/c/` 不一致（commit 9264fca 重构残留），导致 .class 编译到错误目录、surefire discovery 加载失败（ClassNotFoundException: ChatWebSocketHandler）。本票移动两个测试文件到 `controller/patient/chat/` 修复，不视为越票（阻塞测试验证的预存 bug）。
