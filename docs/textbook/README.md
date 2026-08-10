# 智愈（zhiyu-health）分模块代码讲解教材

本目录是一套"教科书式"的项目代码讲解教材：按**业务模块**切分，每个模块一章，章内给出"小程序 → server-java → server-py → admin → 契约/ADR"的完整代码地图、真实代码摘录（带 `路径:行号`）与讲解提示。

## 全局架构速览

请求链路：端（小程序 / admin）→ server-java（鉴权 / 审计 / 限流 / 规则引擎 / 业务写入）→ SSE 调 server-py → LLM / 工具 → token 逐跳透传回端。

- `server-java/`：Spring Boot + MyBatis-Plus，唯一对外入口和业务写入方
- `server-py/`：FastAPI + LangChain/LangGraph Agent 层，唯一调 LLM 的地方
- `miniprogram/`：支付宝原生小程序（C 端）
- `admin/`：React + Umi + Ant Design（B 端）
- 存储分工：PostgreSQL（业务）、Neo4j（医学知识图谱）、Redis（号源计数）、MinIO（图片）
- 两个单一事实源：`contracts/`（24 个跨栈契约 JSON）与 `docs/adr/`（架构决策记录）

## 统一讲法（每章四拍）

1. 业务流程图（从契约 JSON 的状态机讲起）
2. 沿"小程序页面 → server-java service → 存储"走一遍代码
3. 涉及 AI 的部分进 server-py，并标注工具调用链路（`@tool` 定义 → runner 注册 → callback 回调 → server-java `controller/agent/` 承接）
4. 用对应 ADR 回答"为什么这么设计"

## 章节索引

### 基础模块（先修课）

| 章 | 主题 | 一句话 |
|---|---|---|
| [基础模块A](./module-00a-auth.md) | 登录与鉴权 | 一套 JWT 三种受众：患者 / 员工 / server-py 回调密钥 |
| [基础模块B](./module-00b-crosscutting.md) | 横切设施 | 过滤器链、统一异常、契约三端消费、双装配 seam |

### 业务模块

| 章 | 主题 | 一句话 |
|---|---|---|
| [模块1](./module-01-chat.md) | AI 对话（核心） | 一条消息从触摸屏到 LLM 再回来的完整旅程；红线规则先于 LLM |
| [模块2](./module-02-guidance.md) | 智能导诊 | 零 LLM 意图识别 + 确定性号源卡的降本设计 |
| [模块3](./module-03-booking.md) | 挂号 / 预约 / 支付 | 并发正确性专题：Redis DECR + PG 事务对账，禁止先查后改 |
| [模块4](./module-04-scheduling.md) | 排班与调班 | 状态机驱动的 B 端审批流，与号源联动 |
| [模块5](./module-05-consultation.md) | 在线问诊 | 患者 / 医生双侧工作流；WS 连接内 auth 首帧鉴权 |
| [模块6](./module-06-prescription.md) | 处方 / 购药 / 药房 | 禁忌规则引擎 fail closed；AI 只做通用药品知识的边界 |
| [模块7](./module-07-vision.md) | 拍照分析（视觉 AI） | 五场景注册表：新增拍照场景只改一处；图片 MinIO 旁路 |
| [模块8](./module-08-health.md) | 报告解读与健康档案 | AI 解读 + 观察值溯源的合规设计 |
| [模块9](./module-09-care.md) | 消息与诊后关怀 | 随访闭环；服药打卡 eager 即时排程 |
| [模块10](./module-10-organization.md) | 组织管理（B 端 CRUD 样板） | ServiceImpl + MapStruct 标准范式，讲一页举一反三 |
| [模块11](./module-11-knowledge.md) | 医学知识图谱与检索 | pgvector / Neo4j 双路检索；Protocol 反向注入的分层护栏 |
| [模块12](./module-12-voice.md) | 语音输入 / 输出 | 模拟器看门狗兜底；刻意不走工具循环的对照讲解 |
| [模块13](./module-13-observability.md) | Agent 可观测与情绪识别 | 情绪 judge 串行二次调用；trace 脱敏审计纪律 |
| [模块14](./module-14-demo.md) | 演示运营 | 演示重置 / 冻结 / 知识源切换的边界与保护 |

## 教学顺序建议

1. **先修**：基础模块 A、B（地基）
2. **主线三部曲**：模块 1（对话）→ 模块 3（挂号支付）→ 模块 5 + 6（问诊开方购药）——这三条链路覆盖全书 80% 的设计点
3. **AI 能力扩展**：模块 2、7、8、11、12、13
4. **B 端收尾**：模块 4、10、14

## 附注

- 各章路径书写约定：`service/...`、`controller/...` 等相对路径基于 `server-java/src/main/java/com/zhiyu/health/`；`app/...` 基于 `server-py/`；`pages/...`、`utils/...`、`services/...`、`components/...` 基于 `miniprogram/`；`src/...` 基于 `admin/`。
- `docs/adr/` 下存在两篇编号 0010（《跨栈契约》与《RAG 知识检索》），各章引用时均已写全标题区分。
- 已知实况偏差（各章按真实代码为准）：根 AGENTS.md 提到 admin 使用 Zustand，实际无 zustand 依赖；模块 9 源码注释中"ADR-0017"实应为 ADR-0018；报告解读实际走 `VisionAgentApi` 而非 `ClinicalAgentApi`；`PrescriptionForm.tsx` 位于 `admin/src/pages/Workbench/components/`。
