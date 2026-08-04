# 14 - 拍药盒与药品文字查询

**What to build:** 拍药盒：患者拍药盒照片 -> server-py vision 只提取候选药名 -> 通过业务工具回调 server-java 匹配 PostgreSQL medications -> server-java 调用票 11 的确定性规则能力完成禁忌判断 -> 返回说明书卡片（适应症/用法用量/注意事项）和独立安全结果。同票做文字版：输入药名走同一 server-java 查询与安全检查出口。

**Blocked by:** 09 - 电子处方（medications 表）；11 - 禁忌检测；12 - 报告解读与视觉管道

**Status:** done

本票是四个拍照票里**最异构**的：与 15/16/17"视觉直接出分析卡片"不同，14 是"视觉只提药名 -> 工具回调 server-java -> 规则引擎 -> 双出口"。视觉管道泛化、MinIO 旁路持久化等 15 已建立的模式仍复用，以下重点记录 14 的四个差异化决策（ADR-0025）。

## 差异化点 1：药名匹配 - 商品名 + 通用名双列查

**fact**：规则引擎 `ContraindicationRuleEngine.judge` 收 `medicationIds: List<Long>`，不收药名；`MedicationMapper` 现无按名查询方法；`medications` 表有 `name`（商品名 UNIQUE）与 `generic_name`（通用名）两列。

- [x] `MedicationMapper` 新增 `selectByName`，同时查 `name`（商品名）与 `generic_name`（通用名）两列，支持模糊匹配（LIKE）以应对 vision 提取不完全准确
- [x] vision 提取的候选药名两列都比对，返回候选药品列表；未匹配时告知用户未找到

## 差异化点 2：规则引擎复用 - 直接组装原子件，不走 ContraindicationService.check

**fact**：`ContraindicationService.check` 是 B 端开方入口，会把"已审批处方的药品 ID"并入检查集合做跨处方相互作用；14 是 C 端查单药，不需要这个副作用。

- [x] 新建 server-java service（如 `MedicationLookupService`）组装三个底层原子件：
  - `HealthProfileAllergyMapper.selectAllergens(profileId)` - 取当前激活档案过敏原
  - `ContraindicationFactRepository.load(medicationIds)` - Neo4j 只读拉药品成分 + 禁忌事实
  - `ContraindicationRuleEngine.judge(allergies, medicationIds, facts)` - 纯函数判定
- [x] **不**复用 `ContraindicationService.check`，避开"已审批处方并入"副作用
- [x] 该 service 同时服务拍照版与文字版入口（共用同一查询与规则出口）

## 差异化点 3：双出口 - 两条独立 AI 消息

- [x] 一次查询产出**两条独立 AI 消息**：
  - `kind=medication_info`：说明书卡片（适应症/用法用量/注意事项，来自 `medications.instructions`）
  - `kind=medication_safety`：安全结果（禁忌决定 safe/blocked/review_required + 警告话术 + 引导咨询医生/药师）
- [x] 禁忌判定结果（`ContraindicationResult` 的 decision/messageType/advice）复用 `contracts/contraindication.json` 已有枚举，不新建
- [x] 命中禁忌（blocked）时安全结果卡片突出警告，引导咨询医生或药师（符合硬约束 2"C 端 Agent 不做个性化用药决策，只提供通用药品知识解释并引导"）
- [x] `contracts/sse-events.json` 的 `message_kinds` 新增 `medication_info`、`medication_safety`，双端同步

## 差异化点 4：文字版入口 - composer 加"查药品"

- [x] 会话 composer 加两个入口："拍药盒"（拍照上传，vision 提取药名）与"查药品"（文字输入药名）
- [x] 两者都调同一个 `MedicationLookupService` 查询 + 规则出口，只是输入来源不同
- [x] 拍照版链路：server-py vision 提药名 -> 工具回调 server-java `MedicationLookupService` -> 双出口消息回落会话
- [x] 文字版链路：server-java 直接收药名 -> `MedicationLookupService` -> 双出口消息回落会话

## 15 模板照搬项（视觉管道泛化 + MinIO）

- [x] `scenarios.py` 注册 `"PILL_BOX"` key，绑药盒 OCR prompt（只提候选药名，不做药品分析）+ 候选药名 result_model
- [x] `AgentClient.java:148` scenario 参数化后传 `"PILL_BOX"`
- [x] MinIO 旁路持久化：药盒原图存 MinIO + `messages.kind=image` 存路径（15 已建模式）
- [x] 免责声明标注（硬规则 1）
- [x] C 端 `index.axml` 加 `medication_info`、`medication_safety` 卡片渲染分支（优先抽成 `components/medication-info-card`、`components/medication-safety-card` 组件）
- [x] `miniprogram/utils/message-kinds.js` 注册 `medication_info`、`medication_safety` kind
- [x] 功能落地后在票 19 功能入口气泡配置中点亮"拍药盒"（`feature-bubbles.js` 对应项 `enabled:true` 并接上 action）

## server-java 复用票 11 规则引擎

- [x] server-java 复用票 11 的 `rule/` 确定性规则引擎做当前档案过敏史联动，命中禁忌时阻断推荐并突出警告
- [x] DTO/Entity/View 映射使用 MapStruct；契约值从 `contracts/` 加载

## Comments

- 2026-07-29：明确 vision 只负责识别候选药名；药品业务查询和禁忌决定全部由 server-java 完成。
- 2026-08-04（grilling）：确认 14 的四个差异化决策（ADR-0025）：药名双列查、直接组装规则引擎原子件不走 ContraindicationService.check、双出口两条独立消息、文字版 composer 入口共用出口。14 与 15/16/17 根本不同：视觉只提药名，业务全在 server-java。
- 2026-08-04（done）：全栈落地。contracts 层新增 medication_info/medication_safety kind 与 VISION_PILL_BOX_SCOPE_UNSUPPORTED 错误码；server-py 注册 PILL_BOX 视觉场景（PillBoxRecognition 候选药名 result_model，prompt 严格约束只提名不做药品分析）；server-java MedicationLookupService 直接组装三原子件（selectAllergens + factRepository.load + ruleEngine.judge）不注入 PrescriptionItemMapper，双出口 medication_info/medication_safety 卡片；PillBoxPhotoService 照搬拍照管道模板委托 MedicationLookupService；C 端双卡片组件 + 拍药盒/查药品双入口 composer + 回放分支。server-java 333 测试 + server-py 51 测试全绿，spotless/ruff/mypy 通过。注：MapStruct 未实际使用因本票 view record 字段直接由 ObjectMapper 构造 ObjectNode，无 entity->DTO 映射需求。
