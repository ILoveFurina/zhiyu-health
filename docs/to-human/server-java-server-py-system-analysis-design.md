# 智愈 server-java 与 server-py 系统分析与设计

## 1. 文档目的

本文用于指导“智愈”服务端编码、联调、测试与运行，描述 server-java（业务后端）和 server-py（Agent 层）的职责边界、领域模型、接口、数据、安全规则及非功能设计。本文以编码前方案为视角，不记录实现状态、开发进度或完成清单。

## 2. 建设目标与约束

服务端应支撑患者智能导诊与挂号闭环、医生接诊与电子处方、医院运营管理、医学知识增强和 AI 多模态解读，并满足以下核心约束：

1. server-java 是端侧唯一入口与业务数据唯一写入方，负责鉴权、审计、限流、确定性规则与事务。
2. server-py 只负责编排 Agent、调用模型和只读检索医学知识，不直接写业务库。
3. 红线症状和用药禁忌由 server-java 的确定性规则判断，LLM 只负责表达与解释。
4. 所有 AI 产出均携带“仅供参考，不替代医生诊断”；server-py 生成时注入，server-java 出口兜底。
5. PostgreSQL 保存业务实体，pgvector 保存知识块向量，Neo4j 仅保存医学知识关系，Redis 仅由 server-java 操作号源计数与缓存。
6. 号源扣减使用 Redis 原子 DECR 与 PostgreSQL 事务对账，禁止先查后改。
7. 不引入 Spring Cloud、Dubbo、注册中心或网关中间件，两服务通过受控 HTTP 直连。

## 3. 系统上下文与运行拓扑

```mermaid
flowchart LR
  C["C 端支付宝小程序"] -->|"/api/c/* + SSE"| J["server-java 业务后端"]
  B["B 端 React"] -->|"/api/b/*"| J
  J -->|"Agent 请求 / SSE"| P["server-py Agent 层"]
  P -->|"业务工具回调"| J
  J --> PG[("PostgreSQL 16")]
  J --> R[("Redis 7")]
  P -->|"只读向量检索"| V[("pgvector")]
  P -->|"只读图检索"| N[("Neo4j 5")]
  P --> L["火山方舟/语音服务"]
```

应用进程与全部测试在本地运行；云服务器只承载 PostgreSQL、Redis、Neo4j 数据服务。开发机通过 `.env` 中的地址直连数据服务，凭据不得进入代码、测试、日志或版本库。

## 4. 服务边界与分层

### 4.1 server-java（业务后端）

| 分层 | 职责 | 禁止事项 |
|---|---|---|
| `controller/` | 参数校验、身份装配、响应转换 | SQL、业务流程、局部 try-catch |
| `service/` | 用例编排、事务、状态流转、幂等与一致性 | 绕过统一规则和号源会计入口 |
| `rule/` | 红线症状、过敏与用药禁忌等确定性判断 | 交由 LLM 决定是否拦截 |
| `mapper/` | PostgreSQL 数据访问 | 混入业务决策 |
| `agentclient/` | 调用 server-py、SSE 透传与错误映射 | 暴露给端侧直连 |
| `config/` | 鉴权、审计、限流、异常、契约加载 | 记录患者敏感原文 |

新建 B 端 CRUD service 应继承 MyBatis-Plus `ServiceImpl`；DTO、Entity、View 映射使用 MapStruct；异常统一抛出 `ApiException` 并由全局 advice 转换。

### 4.2 server-py（Agent 层）

| 分层 | 职责 | 禁止事项 |
|---|---|---|
| `api/` | FastAPI 路由、请求校验、流响应 | 业务写入和复杂编排 |
| `agent/` | LangGraph 状态图、提示词、工具循环、事件输出 | 保存业务实体 |
| `tools/` | 工具参数模型与业务回调薄壳 | 承载业务逻辑 |
| `services/` | RAG、图谱、视觉、语音等知识/模型能力 | 写 PostgreSQL 业务表 |
| `db/` | pgvector 与 Neo4j 只读客户端 | 获取写权限 |
| `core/` | 配置、模型客户端、共享契约、通用基础设施 | 硬编码跨栈常量 |

LangChain 与 LangGraph 的用法必须对照 `uv.lock` 锁定版本的官方文档。模型调用通过 OpenAI 兼容协议接入火山方舟。

## 5. 核心业务域

### 5.1 组织与资源域

- 医院：名称、等级、地址、经纬度。
- 科室：隶属医院，包含楼层与位置。
- 医生：隶属科室，包含职称、擅长和照片。
- 排班：医生、日期、时段、总号源、剩余号源、启用状态。

医院—科室—医生—排班形成严格父子关系。删除或停用操作需校验下游业务引用，不以级联删除破坏挂号历史。

### 5.2 患者与健康档案域

- 患者账号是 C 端登录身份，可拥有多份本人/家人健康档案。
- 同一患者账号同一时刻最多一份激活档案；激活切换需原子完成。
- 健康档案保存基础信息与过敏史，并作为挂号、处方、报告解读和健康时间线的服务对象。
- 会话归属患者账号而非健康档案；每轮个性化能力读取当时的激活档案。

### 5.3 会话与 Agent 域

- 会话在首条消息时惰性创建，包含标题、创建时间和最近活跃时间。
- 消息保存角色、消息种类、内容、推理档位和可选报告解读关联。
- 一轮对话先经 server-java 红线规则，再进入 server-py；命中红线时不得继续普通导诊工具链。
- server-py 可执行知识检索、图谱扩展和业务工具；所有业务工具通过 HTTP 回调 server-java。

### 5.4 挂号与接诊域

- 排班采用号源池计数，不创建逐号实体。
- 挂号单关联患者、健康档案、来源会话与排班，保存分配序号、病情摘要及状态。
- 状态流转为 `BOOKED → CANCELLED` 或 `BOOKED → VISITED`，终态不可逆。
- 接诊记录与挂号单一一对应，保存医生诊断结论与医嘱；Agent 产出的病情摘要不等同于医生诊断。

### 5.5 处方与健康内容域

- 药品业务信息以 PostgreSQL `medications` 为唯一权威来源。
- 电子处方关联接诊完成的挂号单与医生，包含多个药品明细。
- 审核状态为 `PENDING`、`APPROVED`、`REJECTED`；通过或驳回均为显式决定，驳回需原因。
- 患者仅能查看已通过且带通俗解读与免责声明的处方。
- 报告解读保存处理状态、结构化结果、上下文摘要、错误码和免责声明，可关联会话。
- 站内消息用于就诊提醒、处方通知和接诊小结等主动关怀，需保证同一业务事件幂等。

## 6. 数据存储设计

| 存储 | 数据范围 | 访问方 | 一致性定位 |
|---|---|---|---|
| PostgreSQL 16 | 全部业务实体、知识文本块与向量 | server-java 读写；server-py 仅 pgvector 知识只读 | 业务事实源 |
| Redis 7 | 排班号源原子计数、短期缓存 | 仅 server-java | 高并发前置计数，需与 PG 对账 |
| Neo4j 5 | 症状、疾病、科室、药品、禁忌及关系 | 仅 server-py 只读 | 医学关系事实源 |

### 6.1 PostgreSQL 关系模型

```mermaid
erDiagram
  HOSPITAL ||--o{ DEPARTMENT : contains
  DEPARTMENT ||--o{ DOCTOR : employs
  DOCTOR ||--o{ SCHEDULE : has
  PATIENT ||--o{ HEALTH_PROFILE : owns
  PATIENT ||--o{ CONVERSATION : starts
  CONVERSATION ||--o{ MESSAGE : contains
  HEALTH_PROFILE ||--o{ APPOINTMENT : receives
  SCHEDULE ||--o{ APPOINTMENT : allocates
  APPOINTMENT ||--o| CONSULTATION_RECORD : results_in
  APPOINTMENT ||--o| PRESCRIPTION : results_in
  PRESCRIPTION ||--|{ PRESCRIPTION_ITEM : contains
  MEDICATION ||--o{ PRESCRIPTION_ITEM : selected_as
  HEALTH_PROFILE ||--o{ REPORT_INTERPRETATION : owns
```

主键使用 bigint identity；时间使用带时区时间戳；重要状态由检查约束限定。常用归属、状态与时间排序字段建立索引。schema 由 `schema.sql` 与幂等 seed 管理，开发期结构变化采用 drop、recreate、seed，不引入迁移工具。

#### 核心表 schema

下表是编码阶段的数据字典基线。`id` 均为 `BIGINT IDENTITY`；未特别说明的业务外键均为 `NOT NULL`；所有时间字段使用 `TIMESTAMPTZ`。

| 表 | 核心字段 | 外键/约束 | 说明 |
|---|---|---|---|
| `hospitals` | `name VARCHAR(100)`、`level VARCHAR(30)`、`address VARCHAR(255)`、`longitude/latitude DOUBLE` | `name` 唯一 | 经纬度用于就近医院排序 |
| `departments` | `hospital_id`、`name`、`floor`、`location` | `hospital_id → hospitals.id` | 科室必须归属医院 |
| `doctors` | `department_id`、`name`、`title`、`specialty TEXT`、`photo_url` | `department_id → departments.id` | 医生不进入 Neo4j |
| `schedules` | `doctor_id`、`schedule_date DATE`、`time_slot`、`total_slots INT`、`remaining_slots INT`、`is_active` | `0 ≤ remaining_slots ≤ total_slots`，`total_slots > 0` | 号源池事实记录；并发前置计数位于 Redis |
| `patients` | `nickname`、`created_at` | `nickname` 唯一 | C 端账号身份 |
| `staff_users` | `username`、`password_hash`、`role`、`doctor_id` | `username` 唯一；医生角色需关联医生 | B 端独立账号体系 |
| `health_profiles` | `patient_id`、`display_name`、`gender`、`birth_date`、`relationship`、`active` | 同一 `patient_id` 仅一条 `active=true` | 患者本人或家人档案 |
| `health_profile_allergies` | `health_profile_id`、`allergen` | 二者联合唯一；档案删除时级联删除 | 供确定性用药规则读取 |
| `conversations` | `patient_id`、`title`、`created_at`、`last_active_at` | 按 `patient_id,last_active_at` 查询 | 归属患者账号，不绑定档案 |
| `messages` | `conversation_id`、`role VARCHAR(20)`、`kind VARCHAR(40)`、`content TEXT`、`effort VARCHAR(10)`、`disclaimer VARCHAR(100)`、`report_interpretation_id` | 会话删除时级联；`kind` 来自共享契约 | 结构化卡片以 JSON 字符串保存；AI 消息的免责声明非空 |
| `appointments` | `patient_id`、`request_id VARCHAR(64)`、`health_profile_id`、`conversation_id`、`schedule_id`、`sequence_number`、`status`、`condition_summary`、`condition_summary_disclaimer` | 患者+请求标识唯一；档案+排班唯一；排班+序号唯一；状态限 `BOOKED/CANCELLED/VISITED` | 挂号单业务事实；病情摘要存在时免责声明非空 |
| `consultation_records` | `appointment_id`、`doctor_id`、`diagnosis`、`advice` | `appointment_id` 唯一 | 医生诊疗结论，不属于 AI 产出 |
| `medications` | `name`、`generic_name`、`specification`、`instructions`、`is_active` | `name` 唯一 | 药品业务信息唯一权威源 |
| `prescriptions` | `appointment_id`、`doctor_id`、`status`、`notes`、`review_reason`、`reviewed_by`、`interpretation`、`disclaimer` | `appointment_id` 唯一；状态限 `PENDING/APPROVED/REJECTED` | `APPROVED` 时解读和免责声明非空 |
| `prescription_items` | `prescription_id`、`medication_id`、`dosage`、`frequency`、`duration`、`notes` | 处方删除时级联 | 一个处方包含一至多条明细 |
| `report_interpretations` | `patient_id`、`health_profile_id`、`conversation_id`、`request_id`、`file_type`、`file_name`、`status`、`result_json`、`error_code`、`disclaimer` | 患者+`request_id` 唯一；状态限 `PROCESSING/SUCCEEDED/FAILED` | `request_id` 作为幂等键 |
| `in_app_messages` | `patient_id`、`type`、`title`、`content`、`disclaimer`、`related_appointment_id` | 挂号单+消息类型唯一 | 防止同一业务事件重复通知 |

状态相关字段需满足以下组合约束：

| 实体状态 | 必须为空 | 必须非空 |
|---|---|---|
| 挂号单 `BOOKED` | `cancelled_at` | 排班、序号、健康档案 |
| 挂号单 `CANCELLED` | — | `cancelled_at` |
| 挂号单 `VISITED` | `cancelled_at` | 对应接诊记录 |
| 处方 `PENDING` | `reviewed_at/reviewed_by` | 至少一条明细 |
| 处方 `APPROVED` | `review_reason` | `reviewed_at/reviewed_by/interpretation/disclaimer` |
| 处方 `REJECTED` | `interpretation` | `reviewed_at/reviewed_by/review_reason` |
| 报告解读 `PROCESSING` | `result_json/error_code` | `request_id/disclaimer` |
| 报告解读 `SUCCEEDED` | `error_code` | `result_json/disclaimer` |
| 报告解读 `FAILED` | `result_json` | `error_code/disclaimer` |

#### 跨存储标识与 Redis key

- Neo4j 药品节点通过 `medication_id` 对齐 PostgreSQL，名称仅为快照；不存在由 Neo4j 回写药品表的路径。
- pgvector 知识块至少包含 `id`、正文、来源、主题、embedding 与可用状态；检索只返回知识内容和来源元数据，不返回业务实体。
- Redis 排班号源 key 统一采用 `schedule:slots:{scheduleId}`，值为非负整数；初始化值取 PostgreSQL `remaining_slots`。
- Redis key 不作为永久事实源。服务启动、排班变更及故障恢复必须走 `SlotAccounting` 的初始化/调整语义，禁止任意覆盖并发中的计数。

### 6.2 医学知识图谱

- 节点类型：症状、疾病、科室、药品、禁忌。
- 关系表达症状关联疾病、疾病推荐科室、药品禁忌与药品相互作用等医学知识。
- 图中药品只保存 `medication_id` 与名称快照作为关联键，不作为业务读取源。
- 图中禁止存放患者、医生、排班、号源或挂号单，也不建立业务实体影子节点。
- GraphRAG 采用 pgvector 召回知识块，再由 Neo4j 一跳扩展相关医学关系。

## 7. 接口体系设计

### 7.1 通用接口规则

- 资源路径使用复数名词；列表查询使用 query 参数；创建返回 `201`，普通成功返回 `200`，无响应体删除返回 `204`。
- 标识符在 JSON 中使用十进制整数；日期为 `YYYY-MM-DD`，时刻为 ISO 8601 带时区字符串；经纬度使用十进制度。
- 分页参数为 `page`（从 1 开始）和 `pageSize`（默认 20、最大 100）；列表响应返回 `items`、`page`、`pageSize`、`total`。
- 创建挂号、报告解读等可重试写操作携带 `requestId`；同一主体、同一 `requestId` 必须返回同一业务结果。
- `Authorization: Bearer <token>` 用于端侧身份；`X-Request-Id` 用于链路追踪。服务间回调使用独立的 `X-Agent-Token`，不得复用患者令牌。

### 7.2 C 端接口

| 方法与路径 | 请求要点 | 成功响应要点 | 主要约束 |
|---|---|---|---|
| `POST /api/c/auth/mock-login` | `nickname` | `accessToken`、`patient` | 仅演示身份，不接收真实支付宝凭证 |
| `GET /api/c/conversations` | `page/pageSize` | 会话摘要分页 | 当前患者范围，最近活跃倒序，最多 50 条 |
| `GET /api/c/conversations/{id}/messages` | `beforeId/limit` | 消息列表 | 校验会话归属 |
| `DELETE /api/c/conversations/{id}` | 无 | `204` | 只删会话与消息，不删业务实体 |
| `POST /api/c/chat/stream` | `requestId`、可选 `conversationId`、`content`、`effort`、`scenario`、可选位置 | `text/event-stream` | 红线规则先于 Agent；首条消息惰性建会话 |
| `GET /api/c/health-profiles` | 无 | 档案摘要列表 | 仅当前患者 |
| `POST /api/c/health-profiles` | 基础信息、`allergies[]` | 档案详情 | 创建第一份档案时默认激活 |
| `PUT /api/c/health-profiles/{id}` | 可编辑基础信息、`allergies[]` | 档案详情 | 校验归属；过敏史整体替换需事务化 |
| `POST /api/c/health-profiles/{id}/activate` | 无 | 激活后的档案 | 单患者仅一个激活档案 |
| `GET /api/c/health-profiles/{id}/timeline` | `types/page/pageSize` | 健康时间线分页 | 聚合挂号、处方、报告和接诊小结 |
| `POST /api/c/appointments` | `requestId`、`healthProfileId`、`scheduleId`、可选 `conversationId/conditionSummary` | 挂号单详情 | 幂等、防超卖、重复挂号冲突 |
| `GET /api/c/appointments` | `healthProfileId/status/page/pageSize` | 挂号单分页 | 仅当前患者及其档案 |
| `POST /api/c/appointments/{id}/cancel` | 可选 `reason` | 取消后的挂号单 | 只允许 `BOOKED → CANCELLED`，成功后返还号源 |
| `POST /api/c/reports/interpretations` | multipart 文件、`requestId`、`healthProfileId`、可选 `conversationId` | 解读任务 | 类型、数量、大小按共享契约校验 |
| `GET /api/c/reports/interpretations/{id}` | 无 | 状态与结构化结果 | 仅任务所属患者可见 |
| `GET /api/c/prescriptions` | `healthProfileId/page/pageSize` | 已审核处方分页 | 只返回 `APPROVED` 且带免责声明的处方 |
| `GET /api/c/messages` | `page/pageSize` | 站内消息分页 | 当前患者范围 |

### 7.3 B 端接口

| 方法与路径 | 请求要点 | 成功响应要点 | 权限/约束 |
|---|---|---|---|
| `POST /api/b/auth/login` | `username/password` | `accessToken`、`staff` | B 端独立账号 |
| `GET/POST /api/b/hospitals` | 查询参数或医院表单 | 分页/医院详情 | 管理员 |
| `PUT/DELETE /api/b/hospitals/{id}` | 医院表单或无 | 详情/`204` | 有下游引用时拒绝删除 |
| `GET/POST /api/b/departments` | `hospitalId` 或科室表单 | 分页/科室详情 | 管理员 |
| `PUT/DELETE /api/b/departments/{id}` | 科室表单或无 | 详情/`204` | 校验医院与医生引用 |
| `GET/POST /api/b/doctors` | `hospitalId/departmentId` 或医生表单 | 分页/医生详情 | 管理员 |
| `PUT/DELETE /api/b/doctors/{id}` | 医生表单或无 | 详情/`204` | 校验排班、员工关联 |
| `GET/POST /api/b/schedules` | 医生、日期范围或排班表单 | 分页/排班详情 | 管理员；不允许直接提交 `remainingSlots` |
| `PUT /api/b/schedules/{id}` | 日期、时段、总号源、启用状态 | 排班详情 | 容量变化通过号源会计增量调整 |
| `GET /api/b/reception/today` | 可选日期 | 医生排班和接诊队列 | 医生仅能查看本人 |
| `POST /api/b/appointments/{id}/consultation` | `diagnosis/advice` | 接诊记录与挂号状态 | 医生本人；只允许 `BOOKED → VISITED` |
| `POST /api/b/appointments/{id}/prescriptions` | `items[]/notes` | 待审核处方 | 医生本人；先执行用药安全规则 |
| `GET /api/b/prescriptions` | `status/page/pageSize` | 处方分页 | 管理员/药师 |
| `POST /api/b/prescriptions/{id}/review` | `decision`、可选 `reason` | 审核后处方 | 驳回必须填写原因；终态不可重复审核 |
| `GET /api/b/dashboard/summary` | `date` | 指标及统计口径 | 管理员 |
| `GET /api/b/knowledge-graph` | `nodeTypes/keyword/limit` | 节点与边 | 管理员；server-java 转调 server-py |
| `GET /api/b/agent-logs` | 时间、工具、结果、分页 | 脱敏 trace | 管理员；不返回患者原文 |
| `POST /api/b/demo/reset` | `confirmation` | 重置摘要 | 管理员；串行、审计、二次确认 |

### 7.4 服务间接口

- server-java → server-py：对话编排、报告/药盒/皮肤/饮食/舌苔解读、知识图谱投影与语音能力。
- server-py → server-java：推荐医生、查询号源、查找医院、创建/查询挂号、读取业务上下文等工具回调。
- 工具回调使用独立服务身份认证，不接受端侧令牌代替；接口按最小权限暴露。
- 所有写操作由 server-java 再次校验患者归属、对象状态与业务约束，不能信任模型参数。

| 调用方向 | 方法与路径 | 用途 | 幂等/安全要求 |
|---|---|---|---|
| server-java → server-py | `POST /agent/chat/stream` | LangGraph 对话流 | 仅 server-java 可调用；透传 `requestId` |
| server-java → server-py | `POST /agent/vision/interpret` | 报告及约定视觉场景解读 | 文件暂存引用有时效；不传本地任意路径 |
| server-java → server-py | `GET /agent/knowledge/graph` | 获取只读图谱投影 | 限制节点数与遍历深度 |
| server-py → server-java | `POST /api/agent/doctors/recommend` | 按科室/位置推荐医生 | 只读；参数白名单 |
| server-py → server-java | `GET /api/agent/doctors/{id}/slots` | 查询医生号源 | 只读；只返回可预约排班 |
| server-py → server-java | `POST /api/agent/hospitals/search` | 按位置和科室找医院 | 只读；经纬度范围校验 |
| server-py → server-java | `POST /api/agent/appointments` | 创建挂号 | 使用 `requestId` 幂等；重新鉴权与规则校验 |
| server-py → server-java | `GET /api/agent/appointments/{id}` | 查询挂号结果 | 校验患者上下文 |
| server-py → server-java | `POST /api/agent/contraindications/check` | 获取确定性安全决定 | server-java 作最终判断 |

### 7.5 统一响应与错误

- 非流式接口采用统一成功/错误结构，错误至少包含稳定错误码、用户可见消息和请求标识。
- 参数错误、鉴权失败、权限不足、对象不存在、状态冲突、限流和服务依赖失败使用可区分的 HTTP 状态。
- server-py 错误由 server-java 白名单映射，禁止把模型、数据库或调用栈细节暴露给端侧。
- 报告视觉错误码及文案从 `contracts/vision-errors.json` 获取，上传边界从 `contracts/upload-limits.json` 获取。

普通成功响应：

```json
{
  "data": {},
  "requestId": "req_01J..."
}
```

分页成功响应：

```json
{
  "data": { "items": [], "page": 1, "pageSize": 20, "total": 0 },
  "requestId": "req_01J..."
}
```

统一错误响应：

```json
{
  "error": {
    "code": "APPOINTMENT_SLOT_UNAVAILABLE",
    "message": "该时段号源已约满，请选择其他时段",
    "details": null
  },
  "requestId": "req_01J..."
}
```

`details` 只用于字段校验等非敏感结构化信息。稳定错误码至少分为 `AUTH_*`、`VALIDATION_*`、`RESOURCE_*`、`APPOINTMENT_*`、`PRESCRIPTION_*`、`VISION_*`、`AGENT_*` 和 `RATE_LIMITED`；用户可见文案不作为程序分支依据。

### 7.6 关键 DTO schema

字段名统一使用 lower camel case。请求 DTO 不接受服务端生成字段，响应 DTO 不直接暴露 Entity。

#### 发起对话 `ChatStreamRequest`

| 字段 | 类型 | 必填 | 校验/语义 |
|---|---|---|---|
| `requestId` | string | 是 | 1–64 字符，本轮请求标识 |
| `conversationId` | integer | 否 | 为空时在首条有效消息上创建会话 |
| `content` | string | 是 | 去除首尾空白后非空；长度上限由入口配置 |
| `effort` | enum | 否 | `auto/quick/deep`，默认来自共享契约 |
| `scenario` | enum | 否 | `triage/interpretation`，默认来自共享契约 |
| `healthProfileId` | integer | 否 | 需属于当前患者；个性化能力使用 |
| `location` | object | 否 | `{longitude, latitude}`，范围来自共享契约 |

#### 创建挂号 `CreateAppointmentRequest`

| 字段 | 类型 | 必填 | 校验/语义 |
|---|---|---|---|
| `requestId` | string | 是 | 患者范围内幂等 |
| `healthProfileId` | integer | 是 | 必须属于当前患者 |
| `scheduleId` | integer | 是 | 排班存在、启用且未过期 |
| `conversationId` | integer | 否 | 必须属于当前患者 |
| `conditionSummary` | string | 否 | AI 病情摘要；持久化前补免责声明或建立明确关联 |

#### 挂号单 `AppointmentView`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | integer | 挂号单标识 |
| `status` | enum | `BOOKED/CANCELLED/VISITED` |
| `sequenceNumber` | integer | 排班内就诊序号 |
| `healthProfile` | object | `{id, displayName}` 最小档案摘要 |
| `hospital` | object | `{id, name, address}` |
| `department` | object | `{id, name, floor, location}` |
| `doctor` | object | `{id, name, title, specialty, photoUrl}` |
| `schedule` | object | `{id, date, timeSlot}` |
| `conditionSummary` | string/null | 病情摘要，不得称为诊断 |
| `createdAt/cancelledAt` | datetime/null | ISO 8601 带时区 |
| `disclaimer` | string/null | 存在 AI 病情摘要时必填 |

#### 提交与审核处方

`CreatePrescriptionRequest.items` 为非空数组，每项包含 `medicationId`、`dosage`、`frequency`、`duration` 和可选 `notes`；同一处方内同一药品不得重复。`ReviewPrescriptionRequest` 包含 `decision=APPROVE|REJECT` 与可选 `reason`，当决定为 `REJECT` 时 `reason` 必填。处方响应应展开药品名称与规格，但不暴露内部审核账号密码等字段。

#### 报告解读结果 `ReportInterpretationView`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id/requestId` | integer/string | 业务标识与幂等标识 |
| `status` | enum | `PROCESSING/SUCCEEDED/FAILED` |
| `file` | object | `{name, type, pageCount}`，不返回服务器路径 |
| `result` | object/null | 识别摘要、重点指标、通俗解释、建议 |
| `error` | object/null | `{code, message}`，仅失败时存在 |
| `disclaimer` | string | 所有状态的可见 AI 结果均携带 |
| `createdAt/updatedAt` | datetime | ISO 8601 带时区 |

## 8. SSE 与 Agent 编排

```mermaid
sequenceDiagram
  participant C as C 端
  participant J as server-java
  participant P as server-py
  participant T as 工具/知识服务
  C->>J: 发送消息（会话、档案、档位、位置）
  J->>J: 鉴权、审计、限流、红线规则
  alt 命中红线
    J-->>C: red_flag + done
  else 未命中
    J->>P: 发起 Agent SSE
    P-->>J: meta
    P->>T: 检索或调用工具
    T-->>P: 结构化结果
    P-->>J: token / message / card
    J->>J: 校验、免责声明兜底、消息持久化
    J-->>C: 逐事件透传
    P-->>J: done
    J-->>C: done
  end
```

- 流事件集合、工具到卡片事件映射及消息种类以 `contracts/sse-events.json` 为唯一事实源。
- `meta` 携带请求/会话元信息；`token` 仅用于增量文本；结构化消息或卡片一次性发送；`done` 表示完整结束。
- server-java 对事件类型、结构和大小做白名单校验，再持久化允许的消息种类。
- 断连需取消下游请求并结束资源；已提交的业务工具结果不得因端侧断连而回滚，客户端通过查询接口恢复最终状态。
- Agent trace 只记录脱敏摘要、工具名、参数类型、耗时和结果，不记录患者敏感原文与完整工具参数。

### 8.1 SSE wire schema

每个事件使用标准 SSE 帧：`event: <name>`、`id: <monotonic-id>`、`data: <single-line-json>`，以空行结束。`data` 必须是 UTF-8 JSON，所有事件均包含 `requestId`；除 `meta` 外均包含 `conversationId`。

| 事件 | data schema | 说明 |
|---|---|---|
| `meta` | `{requestId, conversationId, created}` | 确认请求和会话；`created` 表示是否新建会话 |
| `token` | `{requestId, conversationId, delta}` | 仅追加文本片段，不携带完整消息 |
| `message` | `{requestId, conversationId, message:{id, role, kind, content, disclaimer, createdAt}}` | 完整文本或结构化消息 |
| `doctor_recommendations` | `{requestId, conversationId, messageId, items:[DoctorCard], disclaimer}` | 医生推荐卡列表 |
| `doctor_slots` | `{requestId, conversationId, messageId, doctorId, items:[SlotCard]}` | 可预约排班列表 |
| `hospital_recommendations` | `{requestId, conversationId, messageId, items:[HospitalCard], disclaimer}` | 就近医院列表 |
| `appointment` | `{requestId, conversationId, messageId, appointment:AppointmentView}` | 单次挂号结果 |
| `appointments` | `{requestId, conversationId, messageId, items:[AppointmentView]}` | 挂号单列表卡片 |
| `red_flag` | `{requestId, conversationId, code, title, guidance, callEmergency, disclaimer}` | 最高优先级安全中断事件 |
| `done` | `{requestId, conversationId, finishReason, persistedMessageIds, error}` | 流结束；`error` 仅异常结束时存在 |

`DoctorCard` 至少包含医生、科室、医院、擅长、距离和最近可用号源摘要；`SlotCard` 至少包含排班标识、日期、时段、剩余号源与是否可约。结构化卡片的字段 schema 应与对应 REST View 复用，避免 SSE 与查询接口产生两套模型。

## 9. 确定性安全规则

### 9.1 红线症状

- server-java 在调用模型前检查症状文本及结构化上下文。
- 命中胸痛伴冷汗、意识模糊、呼吸困难等规则组合时，立即中断普通导诊并返回红色警告、尽快就医或拨打 120 的建议。
- 规则测试必须覆盖危险输入触发、正常输入不误触、同义表达与组合条件。

### 9.2 用药禁忌

- 输入包括当前健康档案过敏史、处方/候选药品及 Neo4j 返回的禁忌和相互作用知识。
- server-java 根据确定性规则给出放行、警告或拦截决定；LLM 只能解释决定，不能降低规则等级。
- 医生开方、处方审核、患者查药、药盒解读等入口均复用同一规则能力。

### 9.3 免责声明

- server-py 的系统提示与结构化输出模型强制包含免责声明。
- server-java 在所有 AI 响应出口和持久化前校验并补齐，固定文本来自 `contracts/disclaimer.json`。
- 缺失免责声明不允许作为可见 AI 结果直接返回。

## 10. 号源一致性与并发设计

挂号采用“Redis 原子预扣 + PostgreSQL 事务写入 + 失败精确补偿”：

1. server-java 对排班 Redis 计数执行原子 DECR。
2. 结果小于 0 时立即回补并返回号源不足。
3. 在 PostgreSQL 事务中校验排班状态、写挂号单、分配唯一序号并更新剩余号源。
4. 事务体或提交失败时，只补偿本次已经成功应用的 Redis 变化。
5. 成功后 Redis 与 PostgreSQL 剩余号源应一致；异常差异进入对账告警。

取消挂号使用相反的原子退还流程；排班容量调整和计数初始化使用各自的显式句柄。所有 `SlotCounter` 操作必须经 `SlotAccounting`，防止补偿逻辑分散。数据库唯一约束同时阻止同一档案重复预约同一排班及序号重复。

## 11. 模型与知识能力设计

- 对话、工具调用和视觉理解使用 `doubao-seed-2.1-turbo`；向量化使用 `doubao-embedding-vision`。
- 推理档位 `auto`、`quick`、`deep` 来自共享契约；自动档按场景分配，导诊偏低延迟，报告解读偏高质量。
- LangGraph 状态至少包含会话上下文、场景、推理档位、当前档案摘要、工具结果、安全决定和输出事件。
- 工具函数只做参数校验与 service 调用；业务回调必须设置连接、读取和总超时，并限制重试只用于幂等调用。
- RAG 结果需携带来源标识和相关度信息供内部评估；不可靠检索不得被包装为确定诊断。
- 视觉能力只解读报告文字页和约定场景，不承担原始医学影像诊断。

## 12. 鉴权、权限与隐私

- C 端患者令牌与 B 端员工令牌采用不同受众和校验路径。
- B 端使用角色授权；医生只能访问关联医生身份的排班、挂号患者与处方。
- 所有资源查询执行对象级归属校验，禁止仅凭前端隐藏实现隔离。
- server-java 在统一入口审计请求主体、动作、资源类型、结果和请求标识；敏感正文仅生成不可逆脱敏摘要。
- 密码使用强哈希保存；模型密钥、数据库凭据和服务间密钥只从环境读取，不输出到日志。
- 上传文件校验 MIME、大小、数量和内容可读性；临时文件采用随机名、限定目录和生命周期清理。

## 13. 可观测性与故障处理

- 每个端侧请求生成或接收请求标识，跨 server-java、server-py 和工具回调传递。
- 指标至少覆盖请求量、错误率、延迟、SSE 活跃连接、模型耗时、工具调用、规则命中、Redis/PG 计数差异。
- 日志采用结构化字段，禁止记录 `.env`、令牌、患者原文、报告内容与完整模型提示。
- server-py 或模型不可用时，server-java 返回明确的可重试错误；确定性业务查询和 B 端管理不应被连带阻断。
- Neo4j/pgvector 不可用时，Agent 标记知识增强降级，不能伪称已检索；挂号主事务仍以业务数据为准。
- 演示数据重置需鉴权、二次确认、串行执行并记录审计，不与普通业务请求并发运行。

## 14. 测试设计

### 14.1 server-java

- 使用 MockMvc 验证 HTTP 外部行为、鉴权、权限、错误结构和免责声明兜底。
- service 单测覆盖状态机、对象归属、幂等、禁忌判断及事务失败补偿。
- 红线规则覆盖危险输入与正常输入；ArchUnit 强制分层及 `SlotCounter` 访问收口。
- 并发测试以 N 个请求抢最后 1 个号源，断言恰好 1 个成功且 Redis、PostgreSQL 一致。
- 测试配置独立维护，不读取 `.env`，外部 Agent 与数据服务使用 fake 或隔离实例。

### 14.2 server-py

- 使用 FastAPI TestClient 验证 Agent HTTP/SSE、视觉和健康能力接口。
- LLM、server-java 回调、pgvector 与 Neo4j 使用 fake，断言工具调用顺序、参数类型、超时和错误映射。
- 测试推理档位映射、免责声明注入、未知工具、非法结构化输出、流中断与依赖降级。
- 依次执行 pytest、Ruff、mypy 与 import-linter，保持类型和依赖方向可验证。

### 14.3 跨栈契约与集成

- 双栈启动时加载根目录 `contracts/*.json`，缺失、损坏或模型不匹配时快速失败。
- 契约测试对照免责声明、SSE 事件、处方状态、上传限制、视觉错误和对话默认值。
- 集成场景覆盖导诊挂号、红线中断、取消返还、接诊开方、处方审核可见、报告解读和会话恢复。

## 15. 编码与演进约束

- 文件应保持单一职责；controller/路由处理函数不得包含 SQL 或业务逻辑。
- 事务、并发、补偿、原子操作和非直观 SQL 必须就地说明设计原因及失败一致性保障。
- 新增跨栈状态、决定、消息类型或错误码时，先修改 `contracts/`，再更新双栈模型与契约测试。
- 业务多步骤写入应由 server-java service 原子化或显式编排，并向 Agent 暴露单一业务能力接口。
- 医学知识与业务数据保持单向关联和清晰事实源，不以便捷为由双写。
