# 拍照分析原图持久化：MinIO 对象存储 + messages image kind

Status: accepted（票 15/16/17 拍皮肤/拍饮食/拍舌苔；票 54 扩展至医生头像）

票 15/16/17 的拍照分析流程中，患者上传的皮肤/饮食/舌苔照片作为**一等公民**持久化，而非报告解读的"即用即弃"模型。原图持久存于 MinIO 对象存储，PostgreSQL 只存图片路径；`messages` 表新增 `image` kind 承载图片路径，前端识别到该 kind 时按路径回拉 MinIO 原图。

票 54 将此旁路存储扩展至 B 端医生头像：`doctors.photo_url` 由任意 URL 改存 MinIO object key，上传经 server-java 复用 `MinioStorageService.storePhoto`，读取走 server-java 鉴权代理（不开 bucket 公共读）。医生头像同样遵循旁路语义--MinIO 不可用时降级为不留照片、不阻塞档案保存。

## 决策

1. **引入 MinIO 作为图片对象存储**：`server-java` 负责接收 multipart 上传、写入 MinIO、记录路径。MinIO 是**旁路持久化**--只为"历史会话回看原照"服务，不介入视觉分析热路径。server-java 上传 MinIO 后，仍照旧把图片字节流 multipart 透传给 server-py 分析（`AgentClient.interpretVision` 取图链路不变），server-py 零改动、不新增 MinIO 客户端依赖。
2. **`messages` 表新增 `image` kind**：图片消息与文本消息并列存在于对话历史，`content` 存 MinIO 对象路径（key），前端识别到该 kind 时按路径回拉 MinIO 原图。
3. **分析结果卡片仍作为独立 AI 消息回落会话**（kind=`skin_analysis` 等），与图片消息分离--图片是"输入留存"，卡片是"AI 产出"，两者解耦。
4. **报告解读（票 12）保持即用即弃不变**：报告是医疗文书，解读结果才是价值产物；报告原图不迁移到 MinIO，仍走 `ReportUploadStagingService` 内存暂存。两种模型并存，由场景语义决定，不强行统一。

## 为什么不用报告解读的即用即弃模型

- 报告是医疗文书，解读结果是价值产物，原图无独立回看价值且合规负担重（硬约束 5：审计不记敏感原文）。
- 拍照分析的照片对用户有"我拍过什么"的历史回看价值，且皮肤/饮食/舌苔照片本身是用户自有生活影像，隐私敏感度低于医疗报告。
- 持久化原图后，拍照分析卡片能在历史会话中与原图对照呈现，demo 体验更完整。

## 被否决的方案

- **报告解读式即用即弃**：最轻、不引入基础设施，但历史会话看不到原照，拍照分析的"回看"价值丢失。
- **暂存可过期（如 7 天生命周期）**：折中，但引入过期策略后历史会话会出现"图片已失效"的破损体验，demo 评审与日常使用都不可接受；两周 demo 项目不值得为过期清理单独建运维流程。
- **图片 base64 直存 PostgreSQL**：实现最简，但 10MB 单图 base64 后约 13MB，撑爆 messages 行与对话列表查询，且违反"业务数据只存 PostgreSQL、大对象走对象存储"的工程常识。

## Consequences

- 新增 MinIO 依赖：MinIO 作为**云端第四个数据服务**部署，与 PostgreSQL、Redis、Neo4j 并列于云服务器 `compose.yaml`，由用户明确授权改动云端 Compose（覆盖 AGENTS.md 硬约束"未经用户明确要求不改动云端 Compose"）。`server-java` 引入 MinIO SDK，`.env` 增加云端 MinIO 连接配置（endpoint/accessKey/secretKey/bucket），`apiBaseUrl` 同侧直连。本地开发通过 `.env` 中的云端 MinIO 地址直连，与 PG/Redis/Neo4j 同拓扑。
- `messages` 表 `image` kind 需进 `contracts/sse-events.json` 的 `message_kinds`，双端同步，`ContractsConsistencyTest` 钉死。
- 删除会话时图片是否级联删除（MinIO 侧孤儿对象清理）是后续需明确的运维点；两周 demo 范围内可接受孤儿不清理。
- server-py 取图方式已定：**旁路持久化**，server-java 照旧 multipart 字节流透传给 server-py 分析，MinIO 不介入分析热路径，server-py 不新增 MinIO 客户端依赖。MinIO 仅服务"前端历史会话回看原照"。
- **MinIO 不可用时的降级（实现硬约束）**：MinIO 写入失败**不阻断分析主流程**--降级为不留原图（不落 `image` 消息）但分析卡片正常产出回落会话，记可观测错误日志。这使 14-17 在云端 MinIO 未部署时仍可交付与测试：分析主流程、禁忌判定、卡片契约等测试不依赖 MinIO 连接；图片持久化相关测试用 mock MinIO 客户端覆盖。云端 MinIO 部署需远端服务器权限，待权限就绪后启动，不阻塞 14-17 实现与测试。
