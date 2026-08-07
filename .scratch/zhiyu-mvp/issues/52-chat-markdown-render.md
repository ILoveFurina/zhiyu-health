# 52 — C 端对话气泡简单 Markdown 渲染

**What to build:** 小程序聊天页 AI 文本气泡（`pages/chat/index.axml:132-148`）目前用 `<text>{{item.content}}</text>` 原样渲染，LLM 输出的 Markdown 语法（`#` 标题、`**加粗**`、`-`/`1.` 列表、换行）以原始符号裸露给用户。本票为 C 端对话增加轻量 Markdown 识别能力：自写小型解析器（`miniprogram/utils/markdown.js`）把文本解析为块数组（标题/段落/列表项，块内内联分段区分加粗），气泡改为按块渲染并配 acss 样式。不引入第三方 npm 组件库（支付宝原生小程序无现成依赖，且流式逐 token 更新需自研增量友好结构）；不支持表格、代码块、链接等复杂语法——出现即按纯文本兜底，不报错。流式输出（票 40 WS 逐 token 追加、票 51 说明书流复用同一气泡）每次内容更新重新解析，保持光标 `▍` 行为不变。

**Blocked by:** 无（对话流式通道票 40 已完成）

**Status:** claimed

## 施工顺序与 commit 约定

一票一个分支（`t52-chat-markdown-render`）；票内按节分 Conventional commit（`type(scope): 中文摘要`）。顺序：§1 解析器 → §2 气泡渲染与样式 → §3 验证收口。

## 1. 解析器节

- [x] 新增 `miniprogram/utils/markdown.js`：`parseMarkdown(text)` 返回块数组，块类型 `heading`（level 1-3）/ `paragraph` / `list_item`（有序带序号），每块 `segments: [{text, bold}]`；支持 `**bold**`、`#`~`###` 行首标题、`- `/`* ` 无序列表、`1. ` 有序列表、空行分段；未闭合 `**`、表格、代码围栏等按纯文本兜底
- [x] 单测按项目约定：前端不写自动化测试，解析器以纯函数保持简单，人工走查覆盖（已用 node 冒烟验证各语法与兜底路径）

## 2. 气泡渲染与样式节

- [x] `pages/chat/index.js`：AI 文本消息在内容 setData 处同步计算 `blocks = parseMarkdown(content)`（流式每次追加重算）；`content` 原文保留（TTS 播报、复制等仍用原文）
- [x] `pages/chat/index.axml`：AI 文本气泡（`index.axml:132`）改为按 `blocks` 渲染（`a:for` 块 + 块内 `a:for` 分段加粗 `<text class="md-bold">`），流式光标与 disclaimer、TTS 按钮位置不变
- [x] `pages/chat/index.acss`：新增 `md-h1/h2/h3`、`md-p`、`md-li`、`md-bold` 样式，与现有气泡排版协调；检查 TTS 播报（`onPlayTts`）与 pillbox 说明书流仍取原始 `content`

## 3. 验证与收口节

- [ ] 支付宝开发者工具人工走查：普通问答（含 `**加粗**`、列表、标题的回复）、流式过程中符号不裸露闪烁、长说明书流（拍药盒）渲染正常；无控制台错误
- [ ] 票单 checklist 同步更新；README 依赖图节点置 `T52["[x]52 对话Markdown渲染"]`

## Comments

- 2026-08-07（立项）：用户反馈小程序 AI 输出无法识别 Markdown。设计定调为「简单识别」——只覆盖 LLM 健康对话高频语法（标题/加粗/列表/换行），不引入 markdown-it 等完整解析器（npm 体积与流式重算成本不匹配）。
- 2026-08-07（§1-§2 实施）：`utils/markdown.js` 解析为 heading/paragraph/list_item 三类块 + 行内 bold 分段，node 冒烟通过（含未闭合 `**`、表格、四级标题兜底）。接入四处：`streamAssistantToken`（流式重算）、`finishAssistant`、`onFallback`（清空 blocks 防残留）、`drawer.js` 历史回放（仅 assistant 角色）。axml 气泡保留纯文本回退（failRound 错误消息无 blocks）；acss 首块 `:first-child` 去上边距。TTS 播报与 pillbox 说明书流仍取原始 `content`，未受影响。前端验收待支付宝开发者工具人工走查。
