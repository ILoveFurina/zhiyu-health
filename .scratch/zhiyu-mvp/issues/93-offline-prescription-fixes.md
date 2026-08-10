# 93 - 线下接诊开方修复合集：配药数量、禁忌文案、查看只读

**What to build:** 修复 B 端接诊台线下接诊开方的三处问题(在线问诊共享链路一并覆盖)：
1. **提交审核必现「请求失败」**：票 88 在 server-java 处方明细入参 `ItemInput` 新增必填 `quantity`(`@NotNull @Positive`)，admin 前端 `PrescriptionForm` / `prescriptionTemplate` 从未同步，提交缺 `quantity` 被 400 兜底吞成「请求失败，请稍后重试」；处方模板新建/编辑同病。修复为前端逐项补「配药数量」必填正整数输入(默认 1)，`PrescriptionInput`/`PrescriptionTemplateInput` 类型补 `quantity`，模板导入带入数量。
2. **禁忌提示文案面向患者**：B 端开方选药预检展示的是契约 `messages.blocked`(患者口吻「已阻止本次药品推荐…请咨询医生或药师」)与 `advice`(同样患者口吻)，reason 字符串由规则引擎用**药品 id** 拼装(「过敏史“香豆素”与药品 4 的成分/禁忌项匹配」)。C 端 Agent 禁忌检查已按 ADR-0016 移除，该套文案现只在 B 端消费，可放心改为医生向：`messages.blocked`/`messages.review_required`/`advice` 改医生向话术，引擎 reason 改用 PG 药品全名(过敏史×药品 与 药品×药品相互作用两处都不再暴露裸 id)。
3. **已接诊「查看」仍可编辑**：`ConsultationDrawer` 的开方区只被 `hasPrescription` 门控、未按 `completed` 门控，VISITED 状态仍渲染可编辑表单；后端 `requirePrescribableFromAppointment` 只拦已取消、不拦已接诊。修复为前端开方区包 `!completed`、后端拦 VISITED(方案 A：BOOKED 保持可开方)。

**Blocked by:** 无

**Status:** claimed

- [x] contracts：`contraindication.json` 的 `messages.blocked` / `messages.review_required` / `advice` 改医生向话术
- [x] server-java：`ContraindicationRuleEngine` reason 改用 PG 药名(新增 names 入参)、`ContraindicationService` 按候选+在用药并集取药名；`requirePrescribableFromAppointment` 拦 VISITED(409)
- [x] admin：`PrescriptionForm` 逐项「配药数量」输入 + 模板导入带数量 + 预检 Alert 对 REVIEW_REQUIRED 用 warning 色；`prescription.ts`/`prescriptionTemplate.ts` 类型补 `quantity`；`TemplateFormModal` 配药数量输入与回显；`ConsultationDrawer` 开方区 `!completed` 门控
- [x] 测试：规则引擎 reason 断言、服务层禁忌文案断言、VISITED 拦截 service 单测同步更新；t93 分支全量 server-java 922 测试绿；admin typecheck/build 绿
- [ ] 人工走通：接诊台线下接诊开方提交审核不再报错；禁忌预检显示药品全名与医生向文案；已接诊「查看」只读（浏览器实测）
- [ ] 票单置 done 前：README 依赖图 T93 节点已在数字前加 `[x]`（节点已新增，done 时加标记）

## Comments

- 2026-08-11 立项：B 端线下接诊开方暴露三处问题。问题 2 的机制疑问(过敏史来源与禁忌判定)在 grilling 中解答：过敏史读当前激活健康档案的 `health_profile_allergies`，禁忌由 server-java 确定性规则引擎以「去空白+小写双向子串包含」匹配 Neo4j 药品成分/禁忌节点 allergen，知识不可用时 fail closed 返回 REVIEW_REQUIRED。决策记录：配药数量逐项输入(默认 1)；禁忌文案全量改医生向且 reason 用 PG 药名(不暴露 id)；查看只读取方案 A(仅拦 VISITED)。
