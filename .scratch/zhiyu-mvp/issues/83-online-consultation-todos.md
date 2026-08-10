# 83 - 首页待办追踪在线问诊进度

**What to build:** 患者在预问诊发送首条有效消息后，首页待办跨健康档案追踪“预问诊进行中 → 待确认病情摘要 → 等待医生接诊 → 医生问诊中”。点击卡片直达对应续接页面；完成、取消、失效或主动放弃后移除。server-java 提供统一只读聚合接口，小程序首页显示时立即刷新并每 10 秒轮询。

**Blocked by:** 55 - 在线问诊主闭环；72 - 首页信息架构

**Status:** done

- [x] contracts/schema：增加预问诊 ABANDONED 终态及放弃动作
- [x] server-java：首条持久化消息边界、跨档案进度聚合、放弃接口与 service 测试
- [x] miniprogram：首页聚合、状态排序、直达续接、10 秒刷新与失败保留
- [x] miniprogram：预问诊页和摘要页提供显式放弃入口
- [x] 回归：受影响 server-java 测试、Spotless、小程序依赖检查
- [x] schema 变更后重建 zhiyu，重启 server-java 并 verify
- [x] 支付宝开发者工具：登录 → 首句话出现待办 → 四态直达 → 放弃移除；控制台无错误
- [x] 票单置 done 前：README 依赖图 T83 节点加 `[x]`

## Comments

- 2026-08-09：经 grilling 确认完整状态边界；支付宝站外模板消息拆为后续独立票，不阻塞本票。
- 2026-08-09：65 个受影响 server-java 测试、Spotless、小程序 JS 语法检查通过；zhiyu 已重建，server-java 已重启，verify 基线全绿（patients=2、staff_users=16）。Windows 应用控制运行时缺少技能规定的文档入口，且支付宝开发者工具无主窗口，未虚报视觉验收，票保持 claimed。
- 2026-08-10：用户完成支付宝开发者工具主链路验收，票单置 done。
