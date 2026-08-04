# 15 - 拍皮肤

**What to build:** 患者拍摄面部/皮肤照片 -> 复用视觉管道 -> 肤质与常见皮肤问题分析 + 护理建议卡片，带免责声明标注。

**Blocked by:** 12 - 报告解读与视觉管道

**Status:** done

本票背负三件事（grilling 确认全部进 15，不拆票）：
1. 视觉管道泛化（interpreter/document/response 三层 scenario 驱动，report 作为第一个场景走泛化路径）
2. MinIO 接入（云端第四数据服务，ADR-0023）
3. 皮肤场景本身（首个拍照分析场景，建可复制模板供 16/17 照搬）

## 视觉管道泛化（scenario 驱动）

- [x] `scenarios.py` 的 `POLICIES` 注册表新增 `"SKIN"` key，绑皮肤 system_prompt + `SkinAnalysis` result_model；report 作为泛化后的第一个场景走同一注册表
- [x] `interpreter.py:33,42` 的 `VisionInterpreter` Protocol 与 `interpret` 返回类型从写死的 `ReportInterpretation` 泛化为 `BaseModel`（按 `policy.result_model` 动态校验）
- [x] `interpreter.py:60-63` 的 `isinstance(result, ReportInterpretation)` 断言与报告专属 `scope_supported` 拒绝逻辑改为场景策略驱动；皮肤场景若无需 scope 拒绝则该策略为空
- [x] `document.py:61,70-71` 的 `scenario: Literal["REPORT"]` 放开为多场景分发；图片归一化/PDF 路由等场景无关预处理保持复用
- [x] `schemas/vision.py:35` 的 `VisionResponse.result` 从写死 `ReportInterpretation` 泛型化或改为分场景 response
- [x] `AgentClient.java:148` 硬编码 `body.part("scenario", "REPORT")` 参数化为方法入参，皮肤场景传 `"SKIN"`

## MinIO 接入（旁路持久化，ADR-0023）

- [x] server-java 引入 MinIO SDK，新增 `MinioStorageService`（或等价）封装上传/取路径
- [x] `.env` 增加 `MINIO_ENDPOINT`/`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`/`MINIO_BUCKET`；`application.yml` 读取
- [x] server-java 收到 multipart 上传后：照旧把字节流透传 server-py 分析（`interpretVision` 取图链路不变）+ 同时写一份进 MinIO
- [x] MinIO 写入失败不阻断分析主流程（旁路持久化语义），降级为不留原图但分析正常完成，记可观测错误
- [x] compose.yaml 的 minio 服务定义已加（grilling 期间完成）；云端启动需用户授权 SSH 执行 `docker compose up -d minio`

## 皮肤场景

- [x] 皮肤分析 prompt 与 `SkinAnalysis` 结构化 result_model（pydantic）
- [x] 皮肤分析结果卡片作为独立 AI 消息回落会话（`kind=skin_analysis`，落 `messages.content`，轻持久化不建表）
- [x] 原图作为 `kind=image` 消息落 `messages.content`（存 MinIO 对象路径），与卡片消息分离
- [x] 免责声明标注（硬规则 1）
- [x] 异常描述时建议就医的兜底话术
- [x] 会话 composer 加"拍皮肤"入口（仿 report-composer，复用 multipart 上传链路）
- [x] C 端 `index.axml` 加 skin_analysis 卡片渲染分支；优先抽成 `components/skin-card` 组件（与 doctor-card 等同构），而非继续内联
- [x] `miniprogram/utils/message-kinds.js` 注册 `skin_analysis` 与 `image` kind
- [x] 前端识别 `image` kind 时按路径回拉 MinIO 原图

## 契约同步（ContractsConsistencyTest 钉死）

- [x] `contracts/sse-events.json` 的 `message_kinds` 新增 `skin_analysis`、`image`，双端同步
- [x] `contracts/vision-errors.json` 新增皮肤场景范围拒绝码（若皮肤有 scope 概念）或确认无需新增
- [x] `contracts/upload-limits.json` 确认皮肤单图限制沿用现有配置（无需 PDF/多页，皮肤单张图片）

## 入口点亮

- [x] 功能落地后在票 19 的功能入口气泡配置中点亮"拍皮肤"（`feature-bubbles.js` 对应项 `enabled:true` 并接上 action），入口可打开本功能引导卡片

## Comments

- 2026-07-29：明确 vision 只负责识别候选药名；药品业务查询和禁忌决定全部由 server-java 完成。（此注释属票 14，保留以示边界）
- 2026-08-04（grilling）：确认 15 作为拍照分析首票建模板，背负视觉管道泛化 + MinIO 接入 + 皮肤场景三件事。泛化形态为 scenario 驱动（非拆泛化票），持久化为轻（结果进 messages.content 不建表），图片是一等公民（MinIO 持久化 + image kind），server-py 旁路取图零改动，MinIO 部署云端与三件套并列。决策记录见 ADR-0023。
