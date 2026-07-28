# 32 — B 端前端 Umi 重建与组织管理对接

**What to build:** `admin/` 推倒 Vue 脚手架（赛题禁 Vue），按赛题结构模板（`docs/competition-contraint/项目结构.md`）重建为 `@umijs/max` 工程：登录页对接 `/api/b/auth/login` 与 `/me`（token 持久化、未登录路由守卫）；医院/科室/医生三张 CRUD 页对接票 29 已交付 API；`access.ts` 按角色过滤菜单（组织管理仅 admin 可见，doctor 落占位工作台页）。

施工共识（grilling 已拍板）：

- `@umijs/max` 全家桶；antd v5 + `@ant-design/pro-components`，CRUD 用 ProTable（antd 版本冲突再退 v4）
- umi request + dev proxy → `http://localhost:8080`；拦截器统一注入 `Authorization: Bearer`、统一抛后端 `detail` 文案；token 存 `localStorage.staff_token`
- 登录态走 umi `initialState`（`access.ts` 消费）；页面级状态需要时用 Zustand，store 放 `models/`（本票暂无则不建）
- 核心 src 结构严格对齐模板（`config/`、`access.ts`、`app.tsx`、`models/`、`services/`、`pages/[Module]/index.tsx` + 页内 `components/`）；`mock/`、`test/`、`docs/` 不建
- 旧 `admin/src/api/*.ts` 仅作端点形状参考，代码不保留（git 历史即存档）

**Blocked by:** 29 — 票 02 业务迁移：B 端认证与组织管理（Java）

**Status:** ready-for-agent

- [ ] `admin/` Vue 残留清零（无 `.vue` 文件、无 element-plus 依赖）
- [ ] `@umijs/max` 工程可启动，核心 src 结构对齐赛题模板
- [ ] 登录页对接 login + `/me`，token 持久化，未登录访问跳 `/login`
- [ ] 组织三页 CRUD 对真后端可用（proxy → :8080，错误统一抛 `detail`）
- [ ] 角色菜单过滤：admin 见组织管理三页，doctor 落占位工作台页
- [ ] typecheck 通过；手动冒烟（登录 → 三页 CRUD）留记录
- [x] Spec 0002 Element Plus 条款改写为 AntD（本票前置项，已随票单创建完成）
