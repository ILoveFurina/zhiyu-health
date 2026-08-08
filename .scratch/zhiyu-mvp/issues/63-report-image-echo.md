# 63 - 报告解读图片回显（对齐拍药品"留原图"模型）

**What to build:** 报告解读改为"留原图"模型（ADR-0023 同款，与拍药品对齐）：原图存 MinIO + 落 `image` 消息，chat 当前会话即时回显、历史回放回显。

现状（排查结论）：报告解读走"即用即弃"模型——`ReportUploadStagingService` 内存暂存、`take()` 取出即删，只落 `report_upload` 纯文本消息（`ReportInterpretationPersistence`，content 无 object_key）；前端 `report-composer` 推 `report_upload` 文本消息、axml 无图片渲染、drawer 回放也只还原成"已上传报告"文本 → 三层全缺，全程无图。而拍药品/皮肤/饮食/舌苔后端均已落 `image` 消息，历史回放有图；拍药品前端还额外带本地路径 `url: item.path` 做到即时回显。

**用户决策**：已选方向 A（留原图），因知情同意流程已要求用户先遮盖身份信息，默认图片已脱敏。

**Blocked by:** （无）

**Status:** claimed

- [ ] server-java `ReportInterpretationService.interpret()`：`persistence.start()` 后注入 `MinioStorageService`，对过滤掉 PDF 的 files 调 `persistPhotosAndMessages(conversationId, imageFiles)`；保留 `report_upload` 消息不动
- [ ] server-java service 级单测：interpret 成功后断言会话消息含 `KIND_IMAGE` + object_key（MinIO fake），PDF 不落 image 消息
- [ ] miniprogram `report-composer.js` `finishReport`：逐文件推 `kind:'image'` + `url: item.path`（PDF 维持文本），结果卡不变
- [ ] miniprogram `report-composer.js` `consumeReportEntry`：同样推 image 消息（`entry.items` 本地路径）+ waiting → 结果卡
- [ ] miniprogram `drawer.js` 回放：`report_upload` 不再渲染"已上传报告"文本（image 消息已承担回显）；image 回放逻辑已有
- [ ] 文案三处"原件不保存"改"原图留存于历史会话供回看"：`utils/report-picker.js:9`、`pages/chat/index.axml:217`、`pages/report/index.axml:8`
- [ ] 前端浏览器实测：报告（相机/相册多图）→ 即时见图 → 重进会话见图；PDF 走文本无图
- [ ] 票单置 done 前：README 依赖图 T63 节点加 `[x]`、README 依赖连线

## Comments

- 施工记录：本票由排查结论 + 用户方向 A 决策立项（见会话）。回显粒度逐张推 image 气泡（全量 5 图），PDF 维持文本。报告详情页展示原图不做（记录不存 object_key，"查看原会话"已能看图），如需要另起票。工作区现有 t62 未提交遗留，本票提交时不得带入。
