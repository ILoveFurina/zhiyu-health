# Issue tracker: Local Markdown

本仓库的规格说明（spec/PRD）与实施票单存放在 `.scratch/` 下。当前智愈 MVP 的票单位于 `.scratch/zhiyu-mvp/issues/`。

## Conventions

- 每个功能使用独立目录：`.scratch/<feature-slug>/`
- 规格说明位于 `.scratch/<feature-slug>/spec.md`
- 实施票单按一票一文件存放在 `.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号；不得合并成单一票单文件
- 票单顶部附近使用 `Status:` 记录状态；triage 角色字符串见 `triage-labels.md`
- 评论和对话历史追加在文件底部的 `## Comments` 标题下
- 票单之间通过 `Blocked by:` 声明阻塞关系，实施时先处理已解除阻塞的票单

## When a skill says "publish to the issue tracker"

在 `.scratch/<feature-slug>/` 下创建文件；目录不存在时一并创建。属于当前智愈 MVP 的实施票单写入 `.scratch/zhiyu-mvp/issues/`。

## When a skill says "fetch the relevant ticket"

读取用户给出的票单路径或编号对应的文件。当前智愈 MVP 的编号默认在 `.scratch/zhiyu-mvp/issues/` 中解析。

## Wayfinding operations

`/wayfinder` 使用一个 map 文件和每票一个子文件：

- **Map**：`.scratch/<effort>/map.md`，记录 Notes、Decisions-so-far 与 Fog
- **Child ticket**：`.scratch/<effort>/issues/NN-<slug>.md`；正文记录问题，`Type:` 为 `research`、`prototype`、`grilling` 或 `task`，`Status:` 为 `claimed` 或 `resolved`
- **Blocking**：在顶部附近使用 `Blocked by: NN, NN`；所有列出的票单均为 `resolved` 后才解除阻塞
- **Frontier**：扫描尚未解决、未阻塞且未认领的票单，编号最小者优先
- **Claim**：开始工作前将 `Status:` 改为 `claimed` 并保存
- **Resolve**：在 `## Answer` 下追加结论，将 `Status:` 改为 `resolved`，再把摘要与链接追加到 `map.md` 的 Decisions-so-far
