# 21 — 健康档案（时间线 + 引导卡片 + 家庭档案）

**What to build:** 健康档案完整版：建立 patients 账号与 health_profiles 档案 1:N 模型，过敏史只归属于档案；未创建档案时 AI 页显示引导卡片，一键创建（基础信息 + 过敏史录入——禁忌检测的数据入口）；档案时间线聚合挂号单/电子处方/报告解读记录；支持为家人（如父母）创建多份档案并切换，解读/咨询时按当前档案生效。

**Blocked by:** 09 — 电子处方；12 — 报告解读与视觉管道

**Status:** claimed

- [x] health_profiles 表与 patients 构成 1:N，基础信息和过敏史只存于 health_profiles；档案创建表单支持录入
- [x] 未建档引导卡片出现在 AI 页
- [x] 时间线视图聚合三类记录
- [x] 将挂号单、电子处方和既有 report_interpretations 关联到当前 health_profile，切换档案后只查询该档案记录
- [x] 家庭档案：多档案创建与切换，过敏史等随当前档案生效

## Comments

### 2026-07-29 — 实施与验证

- server-java 全量 161 项测试、Spotless 与 ArchUnit 通过；server-py 全量 48 项测试、ruff、mypy、import-linter 通过。
- miniprogram 依赖安装与本票 JS 语法检查通过。
- 当前环境未发现支付宝小程序开发者工具，尚未完成人工“登录 → 建档 → 家人切换 → 时间线”与控制台验收，因此状态暂保留 `claimed`。
