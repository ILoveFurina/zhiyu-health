# 94 - 对话购药引导

**What to build:** OTC 购药能力（票 74/77/78/79/88 链路）已上线但零引导，镜像票 62/65「智能导诊引导卡」机制做「便捷购药引导卡」，零后端改动、只动 miniprogram/：

1. 聊天页新增便捷购药引导卡（`DRUG_GUIDE`）：复用 kind='feature_guide'（票 65「全程只保留一张引导卡」去重天然生效），steps 为「点名药品与数量 → 选就近院区药房 → 确认下单」；chips 为"用户明确点名药品+明确数量"的示例话术（药品均取自 seed.sql 中 is_prescription=FALSE 的 OTC 品种），点击复用 sendPrompt → sendText 直接发送。纯客户端 UI，不经 SSE、不持久化、不带免责声明（非 AI 产出）。
2. 首页功能宫格新增「便捷购药」入口（健康管理组、「药品订单」之前，capsule 图标），经 globalData 交棒（`pendingDrugGuideEntry`）+ switchTab 到聊天 tab，chat 页 onShow 消费后自动插入引导卡（sending 时不消费，同票 62 约定）。
3. 聊天气泡栏新增「便捷购药」气泡（action='drugGuide'，与「智能导诊」气泡同机制）。

硬边界：chips 只是教用户「怎么开口」的示例话术，不得做成系统/Agent 主动推荐具体药品；引导卡不带免责声明（与导诊卡一致）。

**Blocked by:** 22 — 服药打卡；72 - 首页信息架构（与票 96 同链）

**Status:** done

- [x] miniprogram：`feature-guide.js` 新增 DRUG_GUIDE 常量 + enterDrugGuide/consumeDrugGuideEntry + dispatchFeature 分支
- [x] miniprogram：chat 页 onShow 接线 consumeDrugGuideEntry
- [x] miniprogram：首页宫格「便捷购药」入口（globalData 交棒 + switchTab）
- [x] miniprogram：聊天气泡栏「便捷购药」气泡
- [x] 回归：受影响 JS 过 node --check
- [x] 支付宝开发者工具：登录 → 宫格/气泡进购药引导卡 → chips 点击发送出预览卡 → 与导诊卡互斥（全程只一张）；控制台无错误
- [x] 票单置 done 前：README 依赖图 T94 节点加 `[x]`

## Comments

- 2026-08-11：grilling 共识记录——①方案 A（对话引导卡，镜像票 62 导诊卡机制）落地，零后端改动；②chips 为"用户明确点名药品+数量"的示例话术，只是教用户怎么开口，不越「系统不推荐药品」边界；③浏览药房 OTC 目录的能力另立案 95，本票不含。
- 2026-08-10：施工完成（分支 94-drug-purchase-guide）；改动全部在 miniprogram/，零后端改动。chips 药品（布洛芬/氯雷他定/维生素B2/碳酸钙D3）取自 `server-java/src/main/resources/seed.sql` medications 中 is_prescription=FALSE 品种。受影响 JS 均过 node --check。支付宝开发者工具实测待用户走一遍，票保持 claimed，不虚报视觉验收。
