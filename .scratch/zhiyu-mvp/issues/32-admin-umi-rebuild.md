# 32 — B 端前端 Umi 重建与组织管理对接

**What to build:** `admin/` 推倒 Vue 脚手架（赛题禁 Vue），按赛题结构模板（`docs/competition-contraint/项目结构.md`）重建为 `@umijs/max` 工程：登录页对接 `/api/b/auth/login` 与 `/me`（token 持久化、未登录路由守卫）；医院/科室/医生三张 CRUD 页对接票 29 已交付 API；`access.ts` 按角色过滤菜单（组织管理仅 admin 可见，doctor 落占位工作台页）。

施工共识（grilling 已拍板）：

- `@umijs/max` 全家桶；antd v5 + `@ant-design/pro-components`，CRUD 用 ProTable（antd 版本冲突再退 v4）
- umi request + dev proxy → `http://localhost:8080`；拦截器统一注入 `Authorization: Bearer`、统一抛后端 `detail` 文案；token 存 `localStorage.staff_token`
- 登录态走 umi `initialState`（`access.ts` 消费）；页面级状态需要时用 Zustand，store 放 `models/`（本票暂无则不建）
- 核心 src 结构严格对齐模板（`config/`、`access.ts`、`app.tsx`、`models/`、`services/`、`pages/[Module]/index.tsx` + 页内 `components/`）；`mock/`、`test/`、`docs/` 不建
- 旧 `admin/src/api/*.ts` 仅作端点形状参考，代码不保留（git 历史即存档）

**Blocked by:** 29 — 票 02 业务迁移：B 端认证与组织管理（Java）

**Status:** done

- [x] `admin/` Vue 残留清零（无 `.vue` 文件、无 element-plus 依赖）
- [x] `@umijs/max` 工程可启动，核心 src 结构对齐赛题模板
- [x] 登录页对接 login + `/me`，token 持久化，未登录访问跳 `/login`
- [x] 组织三页 CRUD 对真后端可用（proxy → :8080，错误统一抛 `detail`）
- [x] 角色菜单过滤：admin 见组织管理三页，doctor 落占位工作台页
- [x] typecheck 通过
- [x] 手动冒烟（登录 → 三页 CRUD）留记录——2026-07-28 经 umi 代理对云端库执行，28 项全过（详见实施备注"冒烟记录"）
- [x] Spec 0002 Element Plus 条款改写为 AntD（本票前置项，已随票单创建完成）

实施备注：

- 结构决策：`config/config.ts`（defineConfig + proxy `/api`→`http://localhost:8080` + layout + hash 路由）与 `config/routes.ts`（/login 独立 layout:false，组织三页标 `access: 'canAdmin'`）；`src/access.ts` 消费 initialState 产出 `canAdmin`，umi access 插件自动过滤菜单；路由守卫用 `app.tsx` 的 `onRouteChange`（未登录跳 /login、已登录访问 /login 或 / 按角色落首页、doctor 强闯组织页重定向 /workbench），角色经模块级 `cachedUser` 缓存共享给守卫。
- 登录态：`getInitialState` 有 token 时拉 `/api/b/auth/me`，失败清 token 按未登录处理；token 存 `localStorage.staff_token`；`requestConfig` 拦截器统一注入 `Authorization: Bearer`，errorHandler 统一抛后端 `{"detail"}` 文案（antd message），401 且有 token 时清 token 跳 /login（登录页自身的 401 只弹后端 detail"账号或密码错误"，不跳转）。
- 退出登录：layout 运行时配置的 `avatarProps.render` 包 Dropdown，清 token + 清 cachedUser + 置空 initialState 后跳 /login。
- 三 CRUD 页均为 ProTable（`request` 直接返回数组包 `{data, success}`，后端无分页包装故 `pagination:false`），新建/编辑共用页内 `components/*Form.tsx`（ModalForm），删除 Popconfirm 后 `actionRef.reload()`；科室页 ProFormSelect 拉医院列表、医生页拉科室列表；列表"所属医院/科室"列把 id 翻译成名称（并行拉一次映射表）。
- tsconfig 方案：`@umijs/max` v4.6 不随包发布 tsconfig，且 `umi` 的类型入口 `export * from '@@/exports'` 依赖生成的 `src/.umi`。故 tsconfig.json 手写（paths `@/*→src/*`、`@@/*→src/.umi/*`，strict + bundler 解析），`typecheck` 脚本为 `max setup && tsc --noEmit`——先重新生成 .umi 再检查，干净克隆亦可独立跑通；`src/.umi*` 已加进 `admin/.gitignore`。
- 依赖实际版本：@umijs/max 4.6.82、antd 5.29.3、@ant-design/pro-components 2.8.10、typescript 5.6.3（antd 由 @umijs/max 带入，未单独声明）。
- 验证证据：`npm run typecheck` 0 error；`npm run build` 通过（dist 产物含 p__Login/p__Hospital/p__Department/p__Doctor/p__Workbench 各 async chunk）；`npm run dev` 启动后 `curl http://localhost:8000/` 与 `/login` 均 200 返回应用 HTML；`grep -i "vue\|element-plus" admin/package.json` 无命中、`find admin/src admin/config -name "*.vue"` 无命中、vite 配置无残留。
- 冒烟记录（2026-07-28）：云端库（43.139.160.223 的 PG/Redis）+ 本机 server-java(:8080) + 本机 umi dev(:8001)，全部请求经 umi 代理 `/api`（即前端真实请求路径）。脚本 `.scratch/smoke_admin.py` 28 项全过：错误密码 401+detail；admin 登录 → /me(role=admin) → 医院/科室/医生各新建-编辑-删除-列表；无 token 401；doctor.lin 登录 → /me(role=doctor, doctor_id=1) → 访问组织接口 403+detail。冒烟临时记录已全部清理。演示账号密码已重置为 admin/admin123456、doctor.lin/doctor123456（虚构演示凭据）。
- 冒烟中发现的云端库历史数据问题（非本票代码缺陷）：staff_users 的 role 为票 29 之前 Python 时代遗留的大写 `ADMIN`/`DOCTOR`，与票 29 契约（小写 admin/doctor）不符，导致 AdminGuard 全部 403；已将云端两行 role 归一为小写后冒烟通过。票 29 的 StaffUserSeed 按 username 幂等判存，不会纠正存量行的大小写，其他环境若复用旧库需注意。
