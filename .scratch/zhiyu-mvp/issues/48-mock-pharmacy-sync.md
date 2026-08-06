# 48 - Mock 药店库存同步（演示层）

**What to build:** B 端药品管理页加"同步药店库存"功能：点击后由 server-java 返回一份虚构合作药店库存快照（3 家虚构药店 × 药品库存明细 + 同步时间），页面抽屉展示各药店库存明细与上次同步时间。纯演示层：端点收口 `/api/b/demo/**`（ADR-0022 既定边界），不读写 `medications.stock`，不引入任何药店实体进业务模型；ADR-0026 修订注明"Mock 展示层"例外，CONTEXT.md"平台自营药房"词条同步补一句。覆盖题目 B 端"药品库存同步（与药店数据打通）"的演示画面。

**Blocked by:** 34 - 药品管理（页面载体，已完成）；25 - 演示武器包（demo 命名空间，已完成）

**Status:** ready-for-agent

- [ ] server-java 类路径 JSON fixture：3 家虚构药店（名称/区域，演示数据全部虚构）+ 各自药品库存明细
- [ ] `/api/b/demo/` 下两枚端点：POST 触发同步（返回 synced_at / 药店数 / 记录数）+ GET 库存快照；复用 `AdminInterceptor` admin 鉴权；last_synced_at 进程内保存，重启复位为"未同步"
- [ ] admin Medication 页："同步药店库存"按钮 + 抽屉展示各药店库存明细与上次同步时间
- [ ] 负向断言（MockMvc）：同步动作执行后 medications 列表价格/库存无任何变化
- [ ] ADR-0026 修订：后果补"Mock 药店库存同步为演示展示层，端点在 `/api/b/demo/**`，不触碰业务库存"；CONTEXT.md"平台自营药房"词条补 Mock 展示层一句
- [ ] 浏览器实测无控制台错误，人工走通"同步 → 抽屉明细 → 重复同步时间更新"
