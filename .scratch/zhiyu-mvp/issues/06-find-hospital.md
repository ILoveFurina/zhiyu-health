# 06 - 找医院与地理位置

**What to build:** 用户授权地理位置后，对 Agent 说症状或科室名，即可获得按距离排序的就近医院推荐卡片（含"距您 X km"、地址）。拒绝授权时降级为提示手动选择区域。

**Blocked by:** 05 - 医生推荐与结构化卡片

**Status:** done（分支 `codex/issue-06-find-hospital`，commit 见 git log）

- [x] my.getLocation 授权流程 + 拒绝授权的降级路径
- [x] 医院按经纬度距离排序查询（SQL 距离计算，不引 PostGIS）
- [x] 医院卡片含距离与地址，可联动导诊对话

实施备注：

- 坐标是可信设备数据，沿用票 07 的 `AgentContext` + `ToolRuntime` 模式从注入 context 取用，不进 system prompt、不经模型入参，避免模型誊抄坐标出错。链路：小程序 `my.getLocation` -> `/c/chat` 请求体经纬度 -> server-java `ChatController/ChatService` 透传 -> server-py `AgentChatRequest` -> `AgentContext` -> `find_hospitals` 工具 -> server-java `/api/agent/hospitals/nearby`。
- 距离计算用 Haversine 纯 SQL（`HospitalMapper.selectNearby`，`ORDER BY distance_km ASC`），不引 PostGIS；`acos` 自变量用 `LEAST(1.0, ...)` 钳到 [-1,1]，防止浮点误差使 `acos(>1)` 置 NULL 丢失近距离行。seed 增第二家医院使距离排序可演示。
- `find_hospitals` 无坐标时直接返回 `{hospitals:[], need_location:true}` 降级提示，不回调 server-java；小程序 `hospital-card` 降级态提示授权定位或手动选区。选医院联动导诊（询问该医院科室/医生）。
- `hospital_recommendations` 卡片经 `Message.isAiCardKind` 纳入 `ChatService` 通用卡片分支持久化、出口兜底免责声明，并排除出 `ConversationService.recentContext` 的 LLM 文本上下文（沿用票 05 纪律）。
- 验证：server-java 全套 106 项、server-py 全套 23 项通过，ruff 与 mypy 全绿；Haversine SQL 算法用 Python 独立核验（自点距离 0.0、对称性一致）。真实库冒烟因本地无 DB 凭据未执行，SQL 沿既有 `@Select` 模式。
- 双轴 code-review：Standards 无硬违反（分层、免责声明、卡片排除上下文均符合）；Spec 三项 checkbox 与 What to build 均落实。审查发现的距离展示长尾小数已用 `deriveDataFromProps` 格式化为一位小数修复。
