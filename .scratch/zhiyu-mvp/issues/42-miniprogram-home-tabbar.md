# 42 - 小程序首页功能目录、tabBar 与排版统一

**What to build:** 按 CONTEXT.md"功能目录"词条落地 C 端新信息架构：tabBar 三 tab（首页 / AI对话 / 我的）；首页 = 问候头 + 当前激活健康档案 chip（可切换/创建）+ 两组功能宫格 + 待办横卡；挂号与报告解读获得脱离 Agent 卡片的独立入口；建立全局设计 token 并统一新旧页面排版。视觉延续现有薄荷绿渐变（`app.acss`），不改品牌方向。

**Blocked by:** 41 - C 端预约挂号浏览/直接挂号与报告解读历史 API

**Status:** claimed

- [x] `app.acss` 建立设计 token：主行动色 `--zy-color-primary: #00a870`、三档文字色、`--zy-radius-card: 24rpx`、间距阶梯；旧页（chat/appointments/health/prescriptions/messages/drug-orders）硬编码已机械替换为 token，chat 页交互逻辑未动
- [x] `app.json` 配置 tabBar（首页 / AI对话 / 我的，支付宝 schema `name`/`icon`/`activeIcon`）；图标由 `miniprogram/scripts/generate-tabbar-icons.js` 生成（81×81 PNG，普通/选中双态）；`pages/home/index` 为入口页
- [x] 新增 `pages/home/`：问候头 + 健康档案 chip（含切换弹层与无档案引导卡）+ 宫格（就医服务：智能导诊、预约挂号、报告解读；健康管理：健康档案、我的挂号、电子处方、药品订单）+ 待办横卡（即将就诊的挂号单、待支付药品订单；待支付挂号费待票 35/36 完成后补充）
- [x] 新增预约挂号多页流：`pages/booking/{hospitals,departments,doctors,schedules,confirm}` 逐级 `navigateTo`（栈深 ≤5，名称参数 encodeURIComponent 传递）；确认页调 `POST /api/c/appointments`，号源耗尽/重复挂号展示后端错误文案并刷新余量；定位拒绝时降级默认排序
- [x] 新增 `pages/report/`：上传（复用 `utils/report-upload.js` 拆分出的 stage/finalize）+ 历史解读记录列表；上传经 globalData 传 request_id、`switchTab` 进 chat 由会话页 finalize 并渲染解读卡片（CONTEXT.md"报告解读"定义不变）；历史记录带 conversation_id 的点击进 chat 打开关联会话，无关联的页内浮层展示
- [x] 新增 `pages/profile/`（我的）：账号信息头（昵称首字占位圆）+ 健康档案管理 + 记录列表（挂号单/电子处方/药品订单/报告解读记录）+ 消息中心
- [x] 所有 AI 产出区域带"仅供参考，不替代医生诊断"（全局 `ai-disclaimer` 组件）：存量挂载点审计无缺失，report 页列表摘要与详情卡片均已挂载
- [x] 页面使用 `index.{js,axml,acss,json}`，禁止 `.wxml`/`.wxss`/`wx.*`（全量 grep 无违规）
- [ ] 支付宝开发者工具实测：无控制台错误，人工走通"登录 → 首页宫格 → 挂号流下单 → 报告解读上传 → 我的"——**待人工 IDE 验收，通过后本票方可置 done**

## 施工记录

- 提交：`fdb9298`（tabBar+首页骨架）→ `4e37f6e`（挂号五页流）→ `8e86127`（报告解读入口+旧页 token 统一）
- 顺带修复存量 bug：`pages/chat/drawer.js` 遗留的 `this.stopTypewriter()` 调用（打字机已在票 40 移除），导致打开任何历史会话必抛 TypeError，已删
- 已知折中：报告解读历史列表无时间展示（`ReportView` 未返回 `created_at`，未动 server-java；靠后端倒序保证时序）；`utils/request.js` 附加式增强（非 2xx 错误体挂 `err.detail`，既有调用方零影响）
- 自动验证：全部新增/修改 js `node --check` 通过，全部 json `JSON.parse` 通过，tabBar 图标 PNG 逐张读回确认
