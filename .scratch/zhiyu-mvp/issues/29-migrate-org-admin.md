# 29 — 票 02 业务迁移：B 端认证与组织管理（Java）

**What to build:** 将票 02 已实现并验收的 B 端能力在 server-java 复刻并替换 Python 原件：staff_users + JWT 登录（admin/doctor 角色）；医院（名称/等级/地址/经纬度）、科室（隶属医院、楼层/位置）、医生（隶属科室/职称/擅长/照片）CRUD API；最小 seed（1 医院、2 科室、3 医生）。B 端前端（Umi 重建后）对接新 API；server-py 对应 Python 业务代码确认删除。

**Blocked by:** 28 — server-java 骨架与 server-py 瘦身

**Status:** done（main，commit 见 git log）

- [x] staff_users 表（schema.sql）+ 登录接口 + JWT + 角色字段（admin/doctor）
- [x] 医院、科室、医生 CRUD API 可用，字段与票 02 对齐（含科室楼层/位置，就诊指引卡数据源）
- [x] seed 脚本写入最小组织数据
- [x] MockMvc 测试覆盖登录与三实体 CRUD
- [x] server-py 中对应 Python 业务代码已删除且无引用残留

实施备注：
- 接口契约逐项对齐票 02 Python 原件：snake_case 字段（application.yml 全局 SNAKE_CASE）、`{"detail": ...}` 错误体（ApiException + ApiExceptionHandler）、401/403/404 文案一致；JWT 带 scope=staff + role claim，有效期走 JWT_EXPIRE_MINUTES（默认 480）。
- AuthFilter 收窄放行至 /api/b/auth/login（原 /api/b/auth/ 整段放行会让 /me 无令牌裸奔），并新增 ATTR_AUTH_ROLE 透传；B 端组织接口统一 admin-only（controller/b/AdminGuard）。
- 口令散列用 BCrypt（仅引 spring-security-crypto，不进完整 security 栈）；staff seed 走 StaffUserSeed（口令散列必须走代码，seed.sql 无法计算），密码从 SEED_ADMIN_PASSWORD/SEED_DOCTOR_PASSWORD 注入，缺省跳过、幂等按 username 判存；DEMO_DOCTOR_ID=1 依赖 seed.sql 显式 id（与原 Python doctors[0] 同语义）。
- 组织 seed（1 医院/2 科室/3 医生）票 28 已在 seed.sql 落地，本票未动。
- code-review 落实：AdminGuard 消除三份 requireAdmin 重复；StaffTokens 测试工具消除四份 JWT 样板；补 Department/Doctor 的 doctor 角色 403 用例。
- 未覆盖票面项："B 端前端（Umi 重建后）对接新 API"——admin/ 仍是旧 Vue 脚手架（违反 B 端禁 Vue 约束），Umi 重建未发生，前端对接不在本票勾选范围，待后续票。
- 验证：server-java mvn test 62 绿（含票 28 及工作区既有用例）；server-py pytest 17 绿；server-py 全库 grep 无 staff/organization/b/auth 引用残留（含清理 3 个已删测试的 .pyc 缓存）。
