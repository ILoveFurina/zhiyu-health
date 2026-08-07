# 59 - 医生头像显示接线：C 端 object key 渲染修复 + seed 头像补传

**What to build:** 修复 C 端医生头像"不显示"问题。根因三层：① seed 15 位医生的 `photo_url` 是 MinIO object key（`photos/2026-08-07/<拼音>.jpg`），但对象从未上传到 MinIO（桶内仅有运行时 UUID 图），回拉 404；② C 端医生列表/推荐卡把 key 直接当 `<image src>` 用（key 非 URL）；③ 在线问诊聊天页头部用姓氏文字圆占位，详情 API 不返回 `photo_url`。方案：服务端出口把 `photo_url` 映射为 `/api/c/photos?key=` 相对 URL（与 B 端 `/api/b/photos` 先例同构，前端不感知 key）；seed 头像经脚本生成虚构占位图（PIL 渐变底 + 姓氏文字）幂等上传；后续换写实头像仅替换 `scripts/assets/doctor-avatars/` 目录图片重跑上传。约定记录于 ADR-0023 扩展段。

**Blocked by:** 无

**Status:** claimed

- [x] server-java：`photo_url` 出口映射 helper（key → `/api/c/photos?key=` 相对 URL，空 key 返回空串），应用于 `PatientMedicalDirectoryService` 医生列表、`DoctorRecommendationService` 医生推荐卡；`OnlineConsultationService` 问诊详情补 `doctor.photo_url` 字段（`DoctorView` + 映射）
- [x] miniprogram：`booking/doctors` 列表、`doctor-card` 组件、问诊聊天页医生身份卡三处按 URL 渲染 `<image>`，加载失败（onError）降级姓氏文字圆
- [x] `scripts/`：新增生成+上传脚本——PIL 生成 15 张虚构占位头像（渐变底 + 姓氏文字，无肖像权问题）落 `scripts/assets/doctor-avatars/<拼音>.jpg`，幂等上传至 `photos/2026-08-07/<拼音>.jpg`（与 seed 严格一致），传后 stat 验证，失败可重跑
- [x] 实际执行脚本把 15 张头像补传进云端 MinIO，验证 `stat_object` 全部存在
- [x] 测试（新票从简）：server-java URL 映射 helper 单测（`PhotoUrlsTest`）+ 问诊详情 photo_url 字段随 DTO 映射生效（全量 585 测试绿）
- [ ] 支付宝开发者工具人工走通"预约挂号医生列表 → AI 对话医生推荐卡 → 在线问诊医生身份卡"三处头像显示，无图医生降级文字圆；无红色控制台错误
- [ ] 完成前更新 ADR-0023 施工记录、票单 checklist，并在 README 依赖图将 59 节点标记为 `[x]59`

实施边界：不改 B 端 admin（票 54 已走 `/api/b/photos` 正确渲染）；不做写实头像生成（方舟 Coding Plan key 无 seedream 权限，占位先行、目录可替换）；医生头像不进入问诊消息流（医生身份卡头部展示即可）。

## Comments

- 2026-08-08 施工记录（分支 t59-doctor-avatar-display）：server-java 三处出口映射（MapStruct 表达式须全限定类名，生成 Impl 无 import）+ 问诊身份行补 photo_url 列；miniprogram 三处渲染带 onError 降级；`scripts/doctor_avatars.py` 生成+上传 15 张占位头像（幂等，`--force`/`--upload-only` 可选），已实际补传云端 MinIO 并逐张 stat 验证。server-java 585 测试 + spotless 绿；小程序 node --check 绿。
- 待人工：支付宝开发者工具三处头像显示走通后，勾掉最后两项 checklist、置 done 并在 README 依赖图标记 `[x]59`。换写实头像仅替换 `scripts/assets/doctor-avatars/` 目录图片后重跑脚本。
