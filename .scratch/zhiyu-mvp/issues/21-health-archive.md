# 21 — 健康档案（时间线 + 引导卡片 + 家庭档案）

**What to build:** 健康档案完整版：建立 patients 账号与 health_profiles 档案 1:N 模型，过敏史只归属于档案；未创建档案时 AI 页显示引导卡片，一键创建（基础信息 + 过敏史录入——禁忌检测的数据入口）；档案时间线聚合挂号单/电子处方/报告解读记录；支持为家人（如父母）创建多份档案并切换，解读/咨询时按当前档案生效。

**Blocked by:** 09 — 电子处方；12 — 报告解读与视觉管道

**Status:** done

- [x] health_profiles 表与 patients 构成 1:N，基础信息和过敏史只存于 health_profiles；档案创建表单支持录入
- [x] 未建档引导卡片出现在 AI 页
- [x] 时间线视图聚合三类记录
- [x] 将挂号单、电子处方和既有 report_interpretations 关联到当前 health_profile，切换档案后只查询该档案记录
- [x] 家庭档案：多档案创建与切换，过敏史等随当前档案生效

## Comments

### 2026-07-29 — 实施与验证

- server-java 全量 161 项测试、Spotless 与 ArchUnit 通过；server-py 全量 50 项测试、ruff、mypy、import-linter 通过。
- miniprogram 依赖安装与本票 JS 语法检查通过。
- 支付宝小程序开发者工具中已走通“AI 页引导 → 一键建档 → 基础信息/过敏史 → 健康时间线”；页面无运行时错误，控制台仅有本机 HTTP 调试协议警告。验收使用内存假数据服务，结束后已关闭并恢复本机 8080 配置，未改动云端数据。
