# 61 — 报告驱动的健康档案概要与健康观测

**What to build:** 在票 12 的多模态报告解读和票 21 的健康档案时间线之上，完成一条 C 端垂直闭环：体检/检验报告解读成功后，server-java 从报告原始项目名按受控契约确定性映射少量健康观测，携带明确检查日期、来源与核验状态写入 PostgreSQL；健康档案页由“基础资料 + 过敏史 + 时间线”重排为“当前服务对象 + 重要健康信息 + 有数据的健康指标 + 最近报告 + 既有时间线”；患者可在报告详情逐项确认、纠错或排除 AI 提取值。报告原图继续即用即弃，AI 提取永不冒充医生确认，概要和趋势中的报告来源数据统一挂载“仅供参考，不替代医生诊断”。

**Blocked by:** 12 — 报告解读与视觉管道（done）；21 — 健康档案（done）；41 — C 端报告解读历史 API（done）

**Status:** claimed

## 已钉死的产品与领域边界

- 本票建设“报告驱动的健康概要”，不是完整个人健康档案；不新增既往确诊疾病、手术史、家族史、设备同步、日常手工打卡、家属跨账号共管、综合健康分或疾病风险预测。
- 只改 C 端健康档案与报告详情；B 端接诊台、随访计划、医生确认健康观测不在范围内。后续若让医生读取，仅可另票设计真实服务关系、时限、审计与 `USER_CONFIRMED` 可见性。
- 报告原件仍按现有即用即弃模型处理：内存暂存、解读后移除，不落盘、不入 PostgreSQL/MinIO；本票只持久化结构化解读和来源化健康观测，不改变 `CONTEXT.md` 与 ADR-0023 的图片存储边界。
- 一期健康观测白名单固定为：`HEIGHT`、`WEIGHT`、`BMI`、`SYSTOLIC_BP`、`DIASTOLIC_BP`、`FASTING_GLUCOSE`、`TOTAL_CHOLESTEROL`、`ABO_BLOOD_TYPE`、`RH_D_BLOOD_TYPE`。丙氨酸氨基转移酶等其他项目仍正常进入报告解读，但不沉淀为健康观测。
- BMI 只提取报告原值，不根据身高、体重推算；血压报告项由 server-java 确定性拆成收缩压、舒张压两条同日观测；血型是分类观测，只展示最新值，不画趋势。
- LLM/server-py 只输出报告中清晰可见的原始项目名、值、单位、参考范围和日期，不输出 `metric_code`；server-java 才能按 `contracts/health-observations.json` 的别名、类型与单位定义确定性映射并写业务库。未知项目、未知单位、无法解析的值一律不沉淀，不允许模型自由创造指标代码。

## 日期、去重与有效投影语义

- REPORT 结构化结果新增可空 `sample_or_exam_date` 与 `report_date`（ISO `YYYY-MM-DD`）。观测日期 `observed_on` 优先取明确的采样/检查/体检日期，其次取明确报告日期；只识别到年月、日期模糊或两者均缺失时，整份报告照常解读并进入时间线，但不沉淀任何健康观测。上传时间 `created_at` 绝不代替测量日期。
- 一期日期粒度固定到天，每份报告只产生一个 `observed_on`；明确接受“同一健康档案、同一指标、同一天最多一个当前档案值”，不支持一天内多次血压曲线。
- 同一报告内，同一标准指标多次出现时由 server-java 分组处理：标准化后的值和单位完全相同则确定性去重一次；出现不同值则认为存在多次测量或歧义，该指标整组不沉淀，不能取首次/末次，也不能交给 LLM 决定。报告详情仍展示全部原始解读项。
- 不同 `request_id` 重复上传同一纸质报告时，以数据库当前槽位唯一约束收敛，不生成重复趋势点；后到报告仍正常保存解读结果，详情标注“该日期已有档案记录，未重复沉淀”。
- 有效概要/趋势投影只读取 `current = TRUE AND verification_status IN ('UNVERIFIED', 'USER_CONFIRMED')`；`UNVERIFIED` 默认进入概要与趋势并显示“报告提取 · 待核验”，`REJECTED` 与 `SUPERSEDED` 不进入投影。数值指标至少两条有效观测才画趋势，一条只显示最新值；无数据指标不生成 `--` 空卡。
- 只要健康概要展示任意报告来源观测，整个健康指标区固定挂载全局免责声明；即使患者已确认，来源仍是 AI 报告提取，免责声明不移除。

## 数据模型与状态机

- [x] `schema.sql` 新增 `health_observations`：`health_profile_id`、`report_interpretation_id`、`metric_code`、数值/分类值二选一、规范单位、参考范围、`observed_on DATE`、`source_type`、`verification_status`、`current`、`supersedes_id` 自引用及审计时间；REPORT_AI 与 USER_CORRECTION 都必须关联原报告，演示数据不得使用无来源观测。
- [x] 数据库约束：数值/分类值恰好一个非空；指标值类型与状态/来源受 CHECK 约束；`UNIQUE(report_interpretation_id, metric_code) WHERE source_type = 'REPORT_AI'` 保证同报告映射幂等；`UNIQUE(health_profile_id, metric_code, observed_on) WHERE current = TRUE` 保证每日当前槽位唯一并允许保留历史版本。
- [x] 状态来自契约：`UNVERIFIED`（AI 提取未核验）、`USER_CONFIRMED`（患者确认或纠错后的当前值）、`REJECTED`（患者排除，终态但保持 `current=TRUE` 以阻止重复上传复活）、`SUPERSEDED`（被纠错替代，`current=FALSE`）；来源来自契约：`REPORT_AI`、`USER_CORRECTION`。
- [x] 确认操作仅把当前 `UNVERIFIED` 幂等推进为 `USER_CONFIRMED`；纠错只允许修改值，日期、指标代码、单位和来源报告不可编辑，事务内将旧记录置 `SUPERSEDED/current=FALSE` 并追加 `USER_CORRECTION/USER_CONFIRMED/current=TRUE`，可对当前纠错结果再次纠错形成可追溯链；排除把当前可用记录置 `REJECTED`，不可恢复。
- [x] 所有写操作从已鉴权患者与观测关联档案派生归属，不接受请求体传 `patient_id`、`health_profile_id`、`metric_code`、单位、日期或来源；并发确认/纠错/排除用条件更新与每日当前槽位唯一约束收敛，不能出现两个 current 记录。

## 跨栈报告沉淀

- [x] 新增 `contracts/health-observations.json`，唯一维护九项指标代码、中文名、原始项目名别名、值类型、规范单位、允许的单位别名、血型分类值、来源类型、核验状态和患者决定；Java/Python 与前端显示值不得私写枚举，`ContractsTest`/跨栈一致性测试同步。
- [x] server-py `ReportInterpretation` 增加 `sample_or_exam_date`、`report_date`；REPORT prompt 明确只抄录清晰日期，不猜测、不用上传日期补齐，结构化校验只接受完整 ISO 日；报告 item 结构保持面向用户的原始项目名和值，不增加模型生成的 `metric_code`。
- [x] server-java 在模型调用外、报告成功落库事务内完成：选择 `observed_on` → 契约别名映射 → 值/单位规范化 → 血压拆分 → 同报告重复项去重/冲突跳过 → 当前日槽位插入。某项无法沉淀或撞跨报告日槽位时不得让整份报告解读失败；报告 `SUCCEEDED`、结构化 `result_json` 与所有可沉淀观测在同一短事务提交。
- [x] 重复 `request_id` 继续复用原 `report_interpretations`；重复回调/重试不得重复生成观测。新 `request_id` 但同档案/指标/日期由 current 唯一槽位跳过，详情可说明未沉淀原因。

## C 端 API

- [x] `GET /api/c/health-profiles/{profileId}/overview`：校验当前患者拥有档案，返回服务对象、过敏史、最新分类观测、有数据的数值指标及其有效趋势、最近报告摘要、区级免责声明；不返回其他档案数据。
- [x] `GET /api/c/health-profiles/{profileId}/observations?metric_code=...`：返回指定白名单指标按 `observed_on` 排序的有效当前记录及来源/核验状态，供详情与趋势使用；血型只返回最新有效值或由 overview 投影，不作为折线序列。
- [x] `GET /api/c/report-interpretations/{id}`：按已鉴权患者账号归属返回完整报告详情、`conversation_id`、原始 items，以及每个可映射项目的沉淀/待核验/已确认/已排除/重复日槽位/冲突未沉淀状态；同一账号下本人和家人档案均可按归属访问，禁止读取不归当前登录患者账号所有的记录。
- [x] `POST /api/c/health-observations/{id}/confirm`：幂等确认当前未核验观测。
- [x] `POST /api/c/health-observations/{id}/correct`：请求体只含新值，按契约类型校验后追加替代记录。
- [x] `POST /api/c/health-observations/{id}/reject`：终态排除当前记录，不提供恢复端点。
- [x] 新 CRUD/DTO/Entity/View 遵守现有分层：service 继承 `ServiceImpl`，映射使用 MapStruct，controller 零业务逻辑/SQL，异常只抛 `ApiException`。

## C 端页面

- [x] 复用 `pages/health`，不新建第二套健康档案页面：无档案时继续显示现有短建档流程；有档案时按“当前服务对象 → 重要健康信息（过敏史、血型）→ 健康指标 → 最近报告/上传报告 → 健康时间线”重排。
- [x] 已有档案但无观测时，用“上传体检报告，生成健康概要”行动卡引导到现有报告入口；不渲染空指标墙。有数据时显示最新值、单位、`observed_on`、来源和核验徽标；数值型至少两点才画趋势。
- [x] 最近报告只取当前档案，展示报告日期、摘要和需关注数量；进入统一报告详情。当前 `pages/report` 对关联会话记录不再直接跳 chat，而是先打开报告详情，并额外提供“查看原会话”入口（使用详情返回的可信 `conversation_id`）。
- [x] 报告详情逐项展示是否已沉淀及确认/纠错/排除操作；纠错控件固定指标代码、单位和日期，只编辑值；所有操作完成后同步刷新报告详情与健康概要。
- [x] 健康指标区和报告 AI 摘要继续使用全局 `<ai-disclaimer>`；不得因为患者确认而移除固定免责声明。

## Seed、黄金样例与验证

- [x] 黄金报告固定使用 `scripts/assets/report-samples/health-profile-golden-report.png`，内容全部虚构并明确标注“演示样例｜非真实医疗文书”；它只用于人工上传/多模态识别演示，不进入生产静态资源或由服务自动读取。
- [x] `seed.sql` 为林小满增加两条日期更早、内容完全虚构的 `SUCCEEDED` 历史报告解读记录，并由每份报告分别生成白名单观测；`report_interpretation_id` 必须非空，不能用无来源观测偷造趋势。两组日期与黄金报告 `2026-08-06` 形成三点演示趋势，数值保持合理且不构成疾病诊断。
- [x] `DemoResetService` 清表顺序、identity setval、`scripts/verify_zhiyu.py` 的形状/seed 基线随新表同步；schema/seed 完成后必须运行 `uv run python scripts/reset_zhiyu.py`，再运行 `uv run python scripts/verify_zhiyu.py`，不得触碰 `zhiyu_it`/`zhiyu_test`。
- [x] server-py TestClient + fake 覆盖：明确采样日优先、仅报告日降级、仅年月/缺日不沉淀候选、原始项目名保持、免责声明不遗漏；不以真实 LLM 稳定性代替契约测试。
- [x] server-java service 测试覆盖：九项别名/类型/单位映射、BMI 不推算、血压拆分、相同重复去重、冲突重复整项跳过、缺日期整份不沉淀、跨报告同日槽位跳过、报告成功与观测短事务、重复 request 幂等、确认/连续纠错/排除状态机、有效概要与趋势投影、血型不画趋势、免责声明挂载。
- [x] 因本票真正改动唯一约束和并发纠错逻辑，增加 `-Dpg.it=true` PostgreSQL 集成测试覆盖 current 日槽位唯一、并发纠错恰好一个 current 结果、REJECTED 阻止重复上传复活；MockMvc 覆盖上述 C 端端点主链路及他人档案/报告/观测不可读写。
- [ ] 支付宝开发者工具人工走通：林小满登录 → 健康档案已有两点趋势 → 上传黄金报告 → 多模态解读 → 档案形成第三点且显示“待核验”与免责声明 → 报告详情确认一项、纠错一项、排除一项 → 概要/趋势同步 → 查看原会话；全程无红色控制台错误。
- [ ] 同步 `.scratch/zhiyu-mvp/spec.md` 新增对应用户故事/决策；票单置 `done` 前确认 `README.md` T61 节点改为 `[x]61`，并复核 `CONTEXT.md`/ADR-0031 无实施漂移。

## Comments

- 2026-08-08（grill-with-docs 共识）：产品范围、日期语义、九项白名单、server-java 确定性映射、同日 current 槽位、同报告冲突跳过、UNVERIFIED 投影、追加式纠错、免责声明、端点、seed 来源和 B 端排除范围均已逐项确认；领域语言见 `CONTEXT.md`，不可覆盖决策见 ADR-0031，产品/竞品依据见 `docs/research/health-profile-market-fit.md`。
