# 14 — 拍药盒与药品文字查询

**What to build:** 拍药盒：患者拍药盒照片 → server-py vision 只提取候选药名 → 通过业务工具回调 server-java 匹配 PostgreSQL medications → server-java 调用票 11 的确定性规则能力完成禁忌判断 → 返回说明书卡片（适应症/用法用量/注意事项）和独立安全结果。同票做文字版：输入药名走同一 server-java 查询与安全检查出口。

**Blocked by:** 09 — 电子处方（medications 表）；11 — 禁忌检测；12 — 报告解读与视觉管道

**Status:** ready-for-agent

- [ ] 复用视觉管道，药盒 prompt 提取药名与关键信息
- [ ] server-py 不直连业务表；候选药名通过 HTTP 工具回调，由 server-java service 匹配 medications 并返回结构化说明书数据
- [ ] server-java 复用票 11 的 `rule/` 确定性规则引擎做当前档案过敏史联动，命中禁忌时阻断推荐并突出警告
- [ ] 文字搜索与图片识别共用同一 server-java 查询和规则出口
- [ ] 说明书卡片、禁忌决定和消息类型从 `contracts/` 推导；DTO/Entity/View 映射使用 MapStruct
- [ ] 免责声明标注（硬规则 1）
- [ ] 功能落地后在票 19 的功能入口气泡配置中点亮“拍药盒”，入口可打开本功能引导卡片

## Comments

- 2026-07-29：明确 vision 只负责识别候选药名；药品业务查询和禁忌决定全部由 server-java 完成。
