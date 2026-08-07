# 54 - 医生照片上传至 MinIO 并在列表/表单展示

**What to build:** B 端医生管理的新建/编辑对话框中，"照片"字段由当前填写图片 URL 改为 antd `Upload` 组件上传图片；图片经 server-java 存入云端 MinIO（复用现有 `MinioStorageService` 的旁路持久化能力），返回 object key 后写入 `doctors.photo_url`。医生列表"照片"列由缩略图预览（antd `Image`）展示。覆盖题目 B 端"医生档案"的图片上传体验，对应评分项"B 端数据完整性"。

**Blocked by:** 无（MinIO 写路径已由 ADR-0023 + 票 15-17 落地，本票为其新增 B 端读取/上传通道）

**Status:** ready-for-agent

**背景与现状（调研结论）**
- MinIO 写路径已集成：`MinioStorageService.storePhoto(MultipartFile)` 返回 object key，`@ConditionalOnProperty(zhiyu.minio.enabled=true)` 控制，默认关闭（本地开发不依赖云存储）
- 缺口 1：无 B 端上传接口。现有上传端点均为 C 端拍照分析（`/api/c/*-photos`），需 doctor/bearer token；B 端 admin 暂无任何 `Upload` 组件
- 缺口 2：无读取/展示路径。`MinioStorageService` 只有 `putObject`，无 presigned-GET 或公共读 URL 端点；前端无法从 object key 还原图片
- 缺口 3：`.env` 默认 `MINIO_ENABLED=false`，云端 MinIO 待启用（`MINIO_ENDPOINT=http://43.139.160.223:9000`）

- [ ] `schema.sql`：`doctors.photo_url` 语义不变（仍存图片引用），但值改为 MinIO object key（如 `photos/doctors/<yyyy-MM-dd>/<uuid>.jpg`）；seed 15 位医生的 photo_url 更新为占位 key（drop + recreate + seed）
- [ ] server-java 新增 B 端上传接口 `POST /api/b/doctors/photos`（admin 鉴权，`MultipartFile`，复用 `MinioStorageService.storePhoto`，返回 `{object_key, url}`）；新增读取接口 `GET /api/b/photos/{objectKey}` 代理流式返回（或 presigned GET URL），鉴权后访问，避免 bucket 公共读
- [ ] server-java：doctor 新建/编辑接口对 `photo_url` 校验为合法 object key 格式（非任意 URL）；MinIO 失败时按 ADR-0023 旁路降级，不阻塞医生档案保存（照片可选）
- [ ] admin `DoctorForm`：`photo_url` 字段替换为 antd `Upload`（单图、JPEG/PNG、≤2MB），上传成功后写入 object key；编辑回显用读取接口展示缩略图
- [ ] admin `Doctor` 列表"照片"列：`Image` 缩略图（40x40 圆角）走读取接口；无图显示占位"-"
- [ ] MockMvc：上传接口鉴权、文件类型/大小校验、MinIO 禁用时降级（返回占位 key 不报错）；读取接口鉴权
- [ ] 启用云端 MinIO：`.env` 设 `MINIO_ENABLED=true` + 真实密钥，确认 bucket `zhiyu-photos` 存在；本地开发保留 `MINIO_ENABLED=false` 降级路径
- [ ] 浏览器实测无控制台错误，人工走通"新建医生上传照片 -> 列表缩略图预览 -> 编辑回显"
