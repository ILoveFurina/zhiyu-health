# 02 — B 端认证与组织管理（医院/科室/医生）

> **Superseded by:** 票 29 — ADR-0009 双栈转向后，本票能力在 server-java 复刻替换，Python 原件废弃。

**What to build:** 管理员用账号密码登录 B 端后台（与患者体系分离的 staff_users，JWT 登录态，管理员/医生角色区分）；可完整维护医院（名称/等级/地址/经纬度）、科室（隶属医院、楼层/位置）、医生（隶属科室/职称/擅长/照片）；最小 seed 数据（1 医院、2 科室、3 医生）。

**Blocked by:** 01 — 项目骨架与基础设施

**Status:** ready-for-agent

- [ ] staff_users 表 + 登录接口 + JWT + 角色字段（admin/doctor）
- [ ] 医院、科室、医生三个管理页面 CRUD 可用
- [ ] 科室表及管理表单包含楼层/位置字段，作为就诊指引卡的数据源
- [ ] 对应 API 有 TestClient 测试
- [ ] seed 脚本写入最小组织数据
