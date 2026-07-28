# 29 — 票 02 业务迁移：B 端认证与组织管理（Java）

**What to build:** 将票 02 已实现并验收的 B 端能力在 server-java 复刻并替换 Python 原件：staff_users + JWT 登录（admin/doctor 角色）；医院（名称/等级/地址/经纬度）、科室（隶属医院、楼层/位置）、医生（隶属科室/职称/擅长/照片）CRUD API；最小 seed（1 医院、2 科室、3 医生）。B 端前端（Umi 重建后）对接新 API；server-py 对应 Python 业务代码确认删除。

**Blocked by:** 28 — server-java 骨架与 server-py 瘦身

**Status:** ready-for-agent

- [ ] staff_users 表（schema.sql）+ 登录接口 + JWT + 角色字段（admin/doctor）
- [ ] 医院、科室、医生 CRUD API 可用，字段与票 02 对齐（含科室楼层/位置，就诊指引卡数据源）
- [ ] seed 脚本写入最小组织数据
- [ ] MockMvc 测试覆盖登录与三实体 CRUD
- [ ] server-py 中对应 Python 业务代码已删除且无引用残留
