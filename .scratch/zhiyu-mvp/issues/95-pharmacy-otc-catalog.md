# 95 - 药房 OTC 目录

**What to build:** C 端「药房 OTC 目录」只读浏览页——患者可查看「哪些院区药房在售哪些 OTC、什么价、有没有货」（现状只有 B 端药师管理页可见），不改任何下单链路：

1. server-java 新只读端点 `GET /api/c/pharmacy-otc-catalog`（患者 JWT，可选 lng/lat）：只列在售（pharmacy_medications.is_on_sale=TRUE）且 OTC（medications.is_prescription=FALSE）的明细，stock=0 的行仍下发；按药房分组返回价格/库存/配送费/预计分钟。排序口径与 `DrugOrderService.otcCandidates` 逐字对齐：lng/lat 齐全按院区真实坐标球面距离升序（缺坐标排最后、距离 null），无定位保持 SQL 医院/院区稳定序、不出距离、不伪造距离、不默认第一家；无 city 入参（demo 单服务城市即全部院区，查询不写死城市）。
2. miniprogram 新页面 `pages/otc-catalog`：按药房分组的卡片列表（距离徽标有定位时才显示），药品行展示药名/通用名/规格/价格/库存，stock=0 标「暂时缺货」且按钮禁用；每味药「去买」仅把 `我想买<药品名>`（**不带数量**，ADR-0032 硬边界：数量必须由用户在对话中明确给出）经 globalData 交棒预填进聊天输入框（`pendingDrugPurchasePrompt`），用户自行补数量并发送，不自动发送。纯客户端 UI，非 AI 产出，不挂「仅供参考」免责声明。
3. 首页健康管理宫格在「便捷购药」后加「药房目录」入口（navigateTo）。

硬边界：只读浏览，不下单、不直跳购药确认页、不做「AI 推荐药房」。

**Blocked by:** 88 — 院区药房库存与模拟履约闭环；94 - 对话购药引导

**Status:** claimed

- [x] server-java：`GET /api/c/pharmacy-otc-catalog` 端点 + `PharmacyOtcCatalogService` + mapper 只读查询（`selectOtcCatalog`）+ MapStruct 行→视图映射
- [x] server-java：service 级单测（过滤处方药/停售、stock=0 保留、分组、无定位稳定序、有定位距离序缺坐标排最后）+ MockMvc 冒烟（200 形状 + 无令牌 401）
- [x] miniprogram：`pages/otc-catalog` 页面（分组卡片/缺货态/空态/失败 toast）+ 首页宫格入口 + 「去买」交棒预填聊天输入框
- [x] CONTEXT.md 新增「药房 OTC 目录」词条；README 依赖图加 T95 节点与 T88/T94 连边
- [x] 回归：受影响测试类 + ArchUnit/ContractsTest 全绿；spotless:check 通过；受影响 JS 过 node --check
- [ ] 支付宝开发者工具：登录 → 宫格「药房目录」→ 分组列表/缺货禁用 → 「去买」切聊天预填「我想买<药品名>」不自动发送；有/无定位两种形态；控制台无错误（待用户）
- [ ] 浏览器不涉及（纯 miniprogram + server-java 票）
- [ ] 票单置 done 前：README 依赖图 T95 节点加 `[x]`

## Comments

- 2026-08-11：grilling 共识记录——①只读浏览不越「自由选购」边界（经用户拍板）：页面不做任何下单动作，「去买」只预填不带数量的话术，数量仍须用户在对话中明确给出；②直跳购药确认页被明确排除（浏览页不携带数量与药房选择上下文，跳转会架空「患者明确选择履约药房」语义）；③「AI 推荐药房」不做，排序只有定位距离序与 SQL 稳定序两种确定性口径，与 otcCandidates 逐字对齐。
- 2026-08-11：施工完成（分支 95-pharmacy-otc-catalog）。haversine 复用 `DrugOrderService.distanceMeters`（private→public static，避免复刻）；图标复用 zy-ico-report（file-list 清单类，字体子集无店铺图标，与 capsule 区分）。支付宝开发者工具实测待用户走一遍，票保持 claimed，不虚报视觉验收。
