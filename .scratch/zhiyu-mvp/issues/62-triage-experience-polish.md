# 62 - 智能导诊体验优化（入口直达 / 引导卡片 / 收敛即出卡 / 号源卡改版）

**What to build:** 五项用户反馈同一票施工：

1. **入口直达**：首页主卡与宫格「智能导诊」不再只是 switchTab 到 AI 对话，经 `globalData.pendingTriageEntry` 交棒（沿用 `pendingReportFinalize` 同款模式），chat 页 onShow 消费后自动进入导诊引导。
2. **引导卡片化**：`feature_guide` 引导从单行文本升级为客户端引导卡（标题 + 副标题 + 三步说明 + 快捷症状 chips，点击即发送"我{症状}，该挂什么科"）。仍为纯客户端 UI，不经 SSE、不持久化、不带免责声明。
3. **命名归一**：chat 气泡栏「AI 诊室」改名「智能导诊」，与主卡入口、宫格文案一致。
4. **收敛即出卡**：`_QUERY_STATUSES` 放开到 `resolution_statuses[:2]`（explicit_booking + resolved），症状收敛到单一科室即短路直查出号源卡，不再等用户开口；加防重复守卫——最近一条助手消息已是号源摘要时 resolved 不再重复直查（explicit_booking 不受限）。`contracts/guided-registration.json` 仅 `_resolution_statuses_doc` 文档更新。
5. **号源卡改版**：修复支付宝组件样式不隔离导致的类名串扰（doctor-card 的 `.doctor{display:flex}` 等规则污染本组件，医生块被横排挤压、文字逐字竖排）——组件全部类名加 `ds-` 前缀；医生信息与时段行 nowrap、医院/地址单行省略；医生条补头像（server-java `DoctorSlotCard` 透出 `photo_url`（`/api/c/photos` 代理 URL，SQL 加 `d.photo_url`），组件渲染圆形头像、加载失败降级姓氏文字圆，与 doctor-card 票 59 同套路）；卡片头部右侧加「更多科室 ›」，组件内直接 navigateTo `/pages/booking/standard-departments/index`（chat 页与自助号源页两宿主零改动）。

**Blocked by:** 50 - 智能导诊科室号源卡（claimed）

**Status:** claimed

- [x] 首页两个入口置 `pendingTriageEntry`；chat onShow 消费（sending 时保留）
- [x] feature_guide 引导卡：feature-guide.js 数据 + chat/index.axml 分支 + index.acss 样式
- [x] feature-bubbles.js label 改「智能导诊」，相关注释同步
- [x] server-py `_QUERY_STATUSES` 放开 resolved + 去重守卫；契约 doc 更新
- [x] server-py 测试：契约消费断言改 `[:2]`、resolved 触发用例、去重守卫用例
- [x] department-slots-card 布局修正 + 「更多科室」+ 医生头像（含 ds- 前缀样式隔离修复）
- [x] `uv run pytest`（受影响模块 43 项）+ ruff + mypy 绿；server-java ContractsTest/ContractsConsistencyTest 绿
- [x] server-java 号源卡 photo_url 透出（mapper SQL + DoctorSlotCard + service 测试断言，37 项相关测试全绿，spotless 绿）
- [ ] 开发者工具实测：入口直达 / chip 发送 / 收敛即出卡 / 布局横排 / 更多科室跳转 / 闲聊不重复出卡
- [ ] 票单置 done 前：README 依赖图 T62 节点加 `[x]`（节点已随立项加入，置 done 时补标记）

## Comments

- 施工记录（t62-triage-experience 分支）：首页入口交棒沿用 `pendingReportFinalize` 同款 globalData 模式；去重守卫锚点从契约 summary_templates 派生，依赖 server-java `recentContext` 排除卡片 JSON（最近助手消息即上轮摘要文本）。工作区另有票 60 未提交遗留（auth.js/report-picker.js/business.py 等），本票提交时不得带入。
- 号源卡布局根因不是组件自身样式：支付宝组件 acss 不做样式隔离，chat 页同时引入的 doctor-card 用裸类名 `.doctor/.slot/.specialty` 定义了 flex 行布局，串扰 department-slots-card 同名类，把医生块压成一排。修复为组件类名全部加 `ds- 前缀`（doctor-card 等旧组件的裸类名风险仍在，出票时再治理）。
- 头像链路：doctors.photo_url（MinIO object key，票 54 已有列，无 schema 变更）→ `DepartmentMapper.selectDoctorSlotRows` SQL 透出 → `DoctorSlotCard.photo_url`（`PhotoUrls.cUrl` 代理 URL）→ 组件圆形头像 + onError 姓氏圆降级；自助号源页与 AI 卡两入口同视图自动覆盖。

