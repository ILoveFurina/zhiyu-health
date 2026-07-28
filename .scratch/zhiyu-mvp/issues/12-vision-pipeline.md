# 12 — 报告解读与视觉管道（奠基票）

**What to build:** 多模态视觉管道：上传（拍照/相册/PDF 文件；文字型 PDF 可抽取文本，扫描型 PDF 用 PyMuPDF 逐页栅格化后交 vision）→ vision 解读 → 结构化通俗解读卡片 + 免责声明标注，并将解读结果持久化。此管道封装为可复用服务，拍药盒/拍皮肤/拍饮食/拍舌苔四票全部复用它。报告解读支持多页报告。

**Blocked by:** 31 — 票 04 拆分迁移

**Status:** ready-for-agent

- [ ] 图片与 PDF 上传链路（my.chooseImage / my.chooseFileFromDisk 或等价支付宝 API）
- [ ] 文字型 PDF 抽取文本；扫描型 PDF 用 PyMuPDF 逐页栅格化为图片后交 vision 处理（含多页）
- [ ] 报告解读结构化卡片输出 + 免责声明标注
- [ ] report_interpretations 表先按 patient/session 持久化原文件类型、结构化解读结果与免责声明；票 21 建档后再关联当前 health_profile 供档案时间线查询
- [ ] 视觉管道封装为独立可复用服务（后续四票只传不同 prompt）
