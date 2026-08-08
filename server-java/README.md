# server-java 模块地图

server-java 是唯一对外业务入口和业务写入方。HTTP 入口先按受众分为 `controller.patient`（`/api/c/**`）与
`controller.staff`（`/api/b/**`），再按业务模块分区；`controller.agent` 是 server-py 回调与业务工具入口。
URL 不随 Java 包名变化。

## 业务模块

| 模块 | 负责什么 | 主要 service 入口 |
| --- | --- | --- |
| `organization` | 医院、院区、科类、科室、医生与标准科室 | `HospitalAdminService`、`DepartmentAdminService`、`DoctorAdminService` |
| `scheduling` | 排班申请、审核、号源窗口和 Redis/PG 对账 | `ScheduleRequestService`、`ScheduleService`、`SlotAccounting` |
| `appointment` | 患者挂号、取消、支付与幂等 | `AppointmentService`、`PaymentService` |
| `consultation` | 接诊、在线问诊、医患消息、随访 | `ReceptionService`、`OnlineConsultationService` |
| `prescription` | 处方、禁忌审核、药品订单与用药打卡 | `PrescriptionService`、`ContraindicationService`、`DrugOrderService` |
| `health` | 健康档案、观测确认/纠错、报告解读 | `HealthProfileService`、`HealthObservationService`、`ReportInterpretationService` |
| `chat` | Agent 对话轮次、预问诊、事件中继与语音 | `ChatRoundService`、`PreconsultationService`、`VoiceService` |
| `vision` | 皮肤、舌苔、饮食、药盒照片分析 | `ConversationVisionPipeline` 与各 `*PhotoService` 场景入口 |
| `demo` | 演示看板、知识源、药房同步与数据复位 | `DemoDashboardService`、`DemoResetService` |
| `common` | 鉴权、患者身份、免责声明与 MinIO 基础能力 | `AuthService`、`PatientService`、`DisclaimerService`、`MinioStorageService` |

同一业务在 `controller`、`service`、`mapper`、`entity` 和 `service/*/mapping` 中使用相同模块名。
寻找实现时先确定受众和业务模块，再沿 controller → service → mapper/entity 阅读；不要从扁平类名列表猜入口。

## 五条核心阅读路线

### 1. 挂号与号源

挂号：`controller.patient.appointment.AppointmentController` → `service.appointment.AppointmentService` →
`service.scheduling.SlotAccounting` → `service.scheduling.SlotCounter` / `mapper.appointment.AppointmentMapper`。
支付：`controller.patient.appointment.AppointmentPaymentController` → `service.appointment.PaymentService`。

关键不变量在 `SlotAccounting`：Redis 原子扣减先取得资格，PostgreSQL 事务失败必须补偿 Redis；取消时反向执行并对账。
`PaymentService` 用收费行锁串行化支付幂等检查，并用条件更新把 `UNPAID` 推进为 `PAID`。

### 2. 在线问诊

患者从 `controller.patient.consultation.OnlineConsultationController` 进入，医生从
`controller.staff.consultation.OnlineConsultationController` 进入，二者汇入稳定门面 `OnlineConsultationService`。
患者建单/取消/重提由 `PatientOnlineConsultationWorkflow` 负责，医生接单/完成/随访由
`DoctorOnlineConsultationWorkflow` 负责；消息与图片旁路由 `OnlineConsultationMessaging` 负责，接口视图集中在
`OnlineConsultationViews`。并发接单最终由
`mapper.consultation.OnlineConsultationMapper.accept` 的条件更新裁决。

### 3. 医生开方与禁忌

`controller.staff.prescription.DoctorPrescriptionController`（在线问诊使用相邻 consultation 入口）→
`service.prescription.PrescriptionService` → `service.prescription.ContraindicationService` →
`rule.ContraindicationRuleEngine`。提交处方时会在同一业务路径重新运行确定性禁忌检查，不能只依赖前端预检。

### 4. 报告进入健康档案

`controller.patient.health.ReportInterpretationController` → `service.health.ReportInterpretationService` →
`ReportInterpretationPersistence.start` → MinIO 原图旁路 → `agentclient.AgentClient` 的 vision 能力 →
`ReportInterpretationPersistence.succeed` → `mapper.health.HealthObservationMapper`。报告成功时观测先以
`UNVERIFIED` 沉淀；患者后续从 `HealthObservationController` 进入 `HealthObservationService`，将其确认、排除或纠错为
稳定值。纠错会废弃旧 current 行并新增一条用户纠正观测，不覆盖历史。

### 5. C 端对话到 server-py

HTTP/SSE 从 `controller.patient.chat.ChatController`、WebSocket 从 `ChatWebSocketHandler` 进入，随后到
`service.chat.ChatRoundService`；HTTP 中间经过薄适配器 `ChatService`。轮次入口先处理幂等与红线，再经
`agentclient.AgentClient` 的 chat 能力调用 server-py，
由 `ChatRoundPersistence` 持久化事件。通用药品知识流由 `MedicationKnowledgeRelay` 单独负责，不进入个性化用药决策。

## AgentClient 能力边界

`AgentClient` 是稳定门面，内部按 `ChatAgentApi`、`VisionAgentApi`、`VoiceAgentApi`、`ClinicalAgentApi`、
`KnowledgeGraphAgentApi` 分开维护协议、超时与错误映射。新增 server-py 能力时放入语义最接近的能力类；只有底层
`WebClient` 与回调 token 配置共享。
