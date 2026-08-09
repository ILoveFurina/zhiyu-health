# 81 - 深度思考流修复与 quick TTFT 回归治理

**What to build:** 修复票 70 的真实方舟 `reasoning_content` 被 LangChain 转换层丢弃、deep 档没有 `thinking` 事件的问题；移除票 50/62 后加入普通对话关键路径的前置 triage judge，由主 Agent 单轮生成受控标准科室工具参数；quick / auto-disabled 只保留单句等待文案。

**Blocked by:** 40 - 对话首响应提速与 WebSocket 实时链路；65 - 智能导诊多科室选择卡；70 - C 端 AI 等待态重构与思维链展示

**Status:** done

- [x] server-py：方舟流式适配保留原始 delta 的 `reasoning_content`，high 档真实下发 thinking
- [x] server-py：删除前置 triage judge，普通 quick 请求只调用主模型一次
- [x] server-py：主 Agent 新增标准科室号源与候选科室两个受控工具，保留两类既有卡片
- [x] contracts：工具名到既有卡事件的映射同步，清理 judge 专属契约字段
- [x] miniprogram：quick / auto-disabled 删除 3 秒后的同义等待文案，工具进度与 deep 等待态不回退
- [x] 自动化：真实 raw chunk 形状、工具调用、直查重试、thinking 不落库与端侧状态覆盖
- [x] 真实模型命令行验收：deep thinking chunk > 0；quick 普通对话 5 次中位数 ≤ 3 秒、最大值 ≤ 5 秒
- [x] 开发者工具/真机验收明确由开发者执行，不由本票 Agent 操控 GUI
- [x] 票单置 done 前：README 依赖图 T81 节点加 `[x]`

## Comments

- 2026-08-09：诊断确认方舟原始 OpenAI 流能收到 `reasoning_content`，但锁定的 `langchain-openai 0.3.34` 转换为 `AIMessageChunk` 时丢弃该非标准字段；票 70 的 fake 直接构造 `additional_kwargs`，形成误验收。
- 2026-08-09：quick 三轮真实串联计时为 4.60–5.64 秒，其中前置 triage judge 2.46–3.09 秒、主 runner 首 token 1.99–2.86 秒。用户决定取消独立 judge，由主 Agent 单轮生成受控工具参数；号源卡与多科室选择卡均保留。
- 2026-08-09：方舟真实工具流暴露锁定版 LangChain 对列表参数增量聚合不稳定；候选卡首轮改用完整节点更新，紧凑可信目录提示生成候选，收到卡后即停止本轮工具循环。真实模型连续 3 次均为 `tool_start → tool_end → department_options → token`；明确科室为单次模型参数生成后直出 `department_slots`。
- 2026-08-09：真实模型验收：quick TTFT 5 次为 1144/3122/680/2073/1111 ms，中位数 1144 ms、最大 3122 ms；deep 首正文 15842 ms，收到 98 个 thinking chunks。
- 2026-08-09：回归通过：server-py 223 passed / 2 skipped；server-java 受影响 56 tests passed；Ruff、format、mypy、import-linter、Spotless 与端侧 quick 等待态 Node harness 均通过。
