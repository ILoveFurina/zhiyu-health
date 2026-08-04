# 拍药盒架构：视觉只提药名 + 工具回调 + 规则引擎 + 双出口

Status: accepted（票 14 拍药盒与药品文字查询）

票 14 与 15/16/17 虽同为"拍照"票，但架构根本不同：15/16/17 是"拍照 -> 视觉直接出分析卡片"，14 是"拍照 -> 视觉只提候选药名 -> HTTP 工具回调 server-java 匹配 medications -> 规则引擎做禁忌判定 -> 返回说明书卡片 + 独立安全结果双出口"。本 ADR 收口这条跨栈协作链路的四个关键决策。

## 决策

### 1. 药名匹配：商品名 + 通用名双列查

`MedicationMapper` 新增 `selectByName`，同时查 `medications.name`（商品名，UNIQUE）与 `generic_name`（通用名）两列。vision 提取的候选药名可能是商品名也可能是通用名，两列都比对；支持模糊匹配（LIKE）以应对 vision 提取不完全准确的情况。返回候选药品列表，未匹配时告知用户未找到。

### 2. 规则引擎复用：直接组装原子件，不走 ContraindicationService.check

14 不复用 `ContraindicationService.check(patientId, medicationIds)`，而是直接组装三个底层原子件：
- `HealthProfileAllergyMapper.selectAllergens(profileId)` - 取当前激活档案的过敏原
- `ContraindicationFactRepository.load(medicationIds)` - 从 Neo4j 拉药品成分 + 禁忌事实（只读）
- `ContraindicationRuleEngine.judge(allergies, medicationIds, facts)` - 纯函数判定

**理由**：`ContraindicationService.check` 是 B 端开方流程的入口，它会把"已审批处方的药品 ID"并入检查集合做跨处方相互作用。14 是 C 端"查单药"场景，患者只是想查一个药盒的说明书和禁忌，不在开方流程里，不需要"已审批处方并入"这个副作用。直接组装原子件更贴合"查单药"语义，且规则引擎的纯函数性质使这种组装是安全的。

### 3. 双出口：两条独立 AI 消息

一次拍照分析（或文字查询）产出**两条独立 AI 消息**：
- `kind=medication_info`：说明书卡片（适应症/用法用量/注意事项，来自 `medications.instructions`）
- `kind=medication_safety`：安全结果（禁忌决定 safe/blocked/review_required + 警告话术 + 引导咨询医生/药师）

**理由**：票单明确要求"说明书卡片"与"独立安全结果"分离。合并成一条消息会弱化禁忌警告的视觉突出（安全结果可能被淹没在说明书里）；一条消息内含两区块则与现有"一个 kind 一个卡片"的渲染模式不一致。两条独立消息让禁忌警告能独立突出显示，且符合 messages 表"一条消息一个 kind"的既有模型。

### 4. 文字版入口：会话 composer 加"查药品"入口

会话 composer 加两个入口："拍药盒"（拍照上传，vision 提取药名）与"查药品"（文字输入药名）。两者都调同一个 server-java 查询 + 规则出口，只是输入来源不同：拍照版由 server-py vision 提取候选药名后工具回调 server-java，文字版由 server-java 直接接收用户输入药名。共用同一查询与安全检查出口，符合票单"文字搜索与图片识别共用同一 server-java 查询和规则出口"的要求。

## 与 15/16/17 的根本区别

15/16/17：视觉管道直接产出分析卡片（skin/diet/tongue analysis），server-py 是分析主体，server-java 只做持久化与转发。

14：视觉管道只负责识别候选药名（不做药品分析），药品业务查询（匹配 medications）和禁忌判定（规则引擎）全部由 server-java 完成。server-py 的角色从"分析主体"退化为"OCR 提名器"，业务逻辑全在 server-java。这符合 AGENTS.md"server-py 只编排 Agent，业务工具必须 HTTP 回调 server-java"的分层约束。

## Consequences

- `MedicationMapper` 新增 `selectByName` 方法（商品名 + 通用名双列查，支持模糊匹配）。
- 14 新建一个 server-java service（如 `MedicationLookupService`）组装过敏原 + facts + 规则引擎，不复用 `ContraindicationService.check`。该 service 同时服务拍照版与文字版入口。
- `contracts/sse-events.json` 新增 `medication_info`、`medication_safety` 两个 message kind，双端同步，`ContractsConsistencyTest` 钉死。
- 禁忌判定结果（`ContraindicationResult`）的 decision/messageType/advice 枚举已有契约（`contracts/contraindication.json`），14 的安全结果卡片复用这些值，不新建枚举。
- 拍照版链路：server-py vision 提药名 -> 工具回调 server-java `MedicationLookupService` -> 双出口消息回落会话。文字版链路：server-java 直接收药名 -> `MedicationLookupService` -> 双出口消息回落会话。两者共用 service 与出口。
- 原图持久化仍走 MinIO 旁路（ADR-0023），与 15/16/17 同构。
