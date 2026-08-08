# 66 - 小程序视觉基线统一（token 补全 / 图标字体 / 空态组件 / 骨架屏 / 微交互）

**What to build:** C 端全 23 页的浅层机械统一，五项同构工作一票施工（grilling 决议：保留医疗绿方向只精致化，动画克制 ≤400ms，零图片资产新增）：

1. **token 补全**（`app.acss`）：阴影三级——`--zy-shadow-card`（`0 2rpx 10rpx rgba(0,0,0,.06)`，现状 46 处手写值的收敛值）、`--zy-shadow-raised`（`0 8rpx 30rpx rgba(28,91,80,.07)`，取自 health）、`--zy-shadow-pop`（弹层）；圆角分级——`--zy-radius-sm: 16rpx` / 现有 `--zy-radius-card: 24rpx` / `--zy-radius-lg: 32rpx`（999rpx 胶囊与 50% 正圆保持字面量）；语义色——`--zy-color-danger: #e64545`、`--zy-color-warning: #b26a00`，出 token 色全部收编（`#d94b4b`/`#c85d5d`/`#0e8a7d`/`#4caf93` 等清零）；动效——`--zy-motion-fast/base/slow`（150/250/400ms）+ `--zy-ease-out`；字阶四档（标题/正文/辅助/弱）。
2. **图标字体换 emoji**：iconfont 线性图标集 base64 内嵌 acss，单色可 CSS 着色，配现有浅绿底圆角方块容器；替换全部 emoji 占位（首页宫格 7 入口、profile 入口列表、report 上传按钮等）。
3. **共用空状态组件**（新建 `components/zy-empty`）：大号弱色图标 + 文案 + 可选 CTA（如"暂无挂号记录 → 去挂号"），接入所有"暂无…"列表页（appointments/report/prescriptions/drug-orders/messages/booking 各页/health 时间线）。
4. **骨架屏 shimmer**：医院列表、消息、处方三页，数据到达前显示灰色轮廓动画，替代"正在加载…"纯文字。
5. **微交互全铺**：可点卡片/按钮 `:active` scale(.98) 150ms；列表卡片 fade + translateY(16rpx) 250ms 逐条 40ms stagger。所有 `@keyframes` 与动画类名统一 `zy-` 前缀（支付宝 acss 不做样式隔离，票 62 前科）；动画只用 transform/opacity。

**Blocked by:** 无

**Status:** done

- [x] app.acss token 扩展（阴影/圆角/语义色/动效/字阶）
- [x] 机械替换：出 token 色、手写阴影、散点圆角全量收编（Grep 验证旧值清零）
- [x] iconfont 接入 + emoji 占位全替换
- [x] components/zy-empty 组件 + 全部空态列表页接入
- [x] 三页骨架屏（hospitals/messages/prescriptions）
- [x] 全页按压态 + 列表浮入（zy- 前缀 keyframes）
- [x] 开发者工具 23 页走查：无控制台错误，免责声明条全页常显不受影响
- [x] 票单置 done 前：README 依赖图 T66 节点加 `[x]`

## Comments

- grilling 决议（2026-08-08）：视觉方向保留票 42 医疗绿不换方向；"不美观"主因定位为 emoji 图标、零动效、空态一行字、杂色四种，均在本票覆盖。chat 页入场动画不在本票——归票 67 独立施工独立回退。
- 施工记录（2026-08-09）：token 在票 42 基础上补阴影三级/圆角 sm+lg/danger+warning 及浅底/border+fill/动效四档/字阶四档；图标为 Remix Icon v4.6.0（Apache-2.0）pyftsubset 裁 15 枚线性图标 base64 内嵌 app.acss（@font-face zyicon + `.zy-ico-*` 类）；⚠/✓/↑↓/⏸ 等文字符号保留非 emoji 占位。zy-empty 接入 14 处空态（appointments 带「去挂号」CTA）；骨架屏三页按真实列表形态；旧 keyframes `tool-spin`/`wait-spin` 已改 `zy-` 前缀。
- 验证记录：具名旧色（#0e8a7d/#4caf93/#d94b4b/#c85d5d 等全表）grep 清零，pages+components 残留 hex 仅 #fff、页渐变例外（#e9f6f2/#f4faf8/#f8faf9）、priority-blue（#4389d8/#d6e4ff/#f0f5ff，无 token 保留）；手写阴影/散点圆角清零；全部 acss 花括号配平；改动 js `node --check` 通过、json 解析通过。
- 走查记录（2026-08-09）：开发者工具 23 页人工走查通过，无控制台错误，免责声明条常显不受影响；票单置 done，合入 main。
