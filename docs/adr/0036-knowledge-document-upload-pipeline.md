# 知识文档上传闭环与 embedding 在线化

Status: accepted

## 背景

知识库（`knowledge_chunks`）此前是纯离线 seed 模式：50 条症状场景知识由 `seed.sql` 灌入文本，向量由 `server-py/app/scripts/seed_embeddings.py` 离线生成、写入 `seed-knowledge.sql`、由 Spring 执行回填。运营方无法在 B 端补充知识，切分策略迭代只能手工改 SQL。ADR-0010 已指出 RAG 的正式产品入口应是"受控证据问答"，但缺乏文档级管理基础设施使这一方向无法落地。

本 ADR 引入 B 端知识文档上传闭环：管理员上传文档 -> 业务后端解析切分 -> Agent 层批量计算 embedding -> 业务后端持久化 chunk。它不改变运行时检索路径（server-py 仍只读 pgvector），只新增"知识如何进入库"的运营链路。

## 决策

1. **上传闭环经 server-java，embedding 计算委托 server-py**：admin 上传文档到 server-java `/api/b`（`MultipartFile`，与现有所有上传入口同构）。server-java 负责文档解析与文本切分（产出 `department/title/content` 列表），再调 server-py `POST /api/agent/knowledge/embeddings`（`AgentCallbackAuth` 共享密钥，与 vision/asr/clinical 同构）批量计算 embedding，最后由 server-java 写 `knowledge_chunks`。切分是业务数据诞生时刻，归业务后端；server-py 的新 endpoint 退化为纯批量 embedding 服务（输入文本列表、输出向量列表，零业务语义）。

2. **与 ADR-0009/0010 边界的关系**：ADR-0010 第 8 点否决了"embedding 或向量写入迁到 server-java"。本决策不违反该否决--embedding 模型调用仍在 server-py（`core/embeddings.py`），向量写入仍在 server-java。新增的 server-py endpoint 不触碰 `knowledge_chunks` 表（不读不写），只是把离线脚本 `seed_embeddings.py` 的 `aembed_documents` 能力以 HTTP 服务形式暴露，与 vision 管道"server-java 收上传 -> server-py 做模型计算 -> server-java 持久化"完全同构。运行时 server-py 对 pgvector 只读的不变量不破（检索路径零改动）。

3. **文档元数据表 + 原文 MinIO 旁路持久化**：新增 `knowledge_documents` 表（`id, file_name, content_type, byte_size, object_key, source, status, department, uploader_staff_id, error_code, error_message, processing_started_at, chunk_count, created_at, updated_at`），原文存 MinIO（复用 `MinioStorageService`，ADR-0023 旁路模式）。`knowledge_chunks` 加 `document_id` 外键列指向文档。MinIO 不可用时（`enabled:false`）`object_key` 置空降级（同 DoctorPhoto 先例），文档元数据与 chunk 正常写库，但无法重新切分（无原文可重读）。

4. **seed 知识纳入文档模型统一管理**：现有 50 条 seed chunk 补 `document_id` 指向一条 `source=SEED` 的系统预置文档行（status 永远 READY，只读不可归档/重切/删除）。上传文档 `source=UPLOAD` 可管理。检索时不区分来源，全部 chunk 平等参与 Top-K（运行时检索 SQL 零改动）。admin 文档列表展示"系统预置（50 chunk）+ 已上传文档（N chunk）"统一视图。

5. **异步四态状态机 `PROCESSING/READY/FAILED/ARCHIVED`**：上传接口立即落库返回 `PROCESSING`，进程内 `@Async` 线程池（无 MQ，AGENTS.md 禁止引入）后台跑完整链（MinIO 存原文 -> 解析切分 -> 调 server-py embedding -> 写 chunk），完成后更新状态。`PROCESSING` 期间该文档无 chunk（不可检索，与 FAILED 一致）。任一步失败标 `FAILED`，保留原文与元数据，admin 可点重试（整链幂等重跑：先删已写 chunk 再重切）。`ARCHIVED` 为 admin 主动归档（软删除文档元数据保留 + 物理删除该文档全部 chunk 行）。这与现有同步上传链路（vision/报告解读）不同，是本平台首个异步运营链路。

6. **孤儿 PROCESSING 恢复**：进程崩溃/重启时 `PROCESSING` 文档会卡死。`knowledge_documents` 加 `processing_started_at` 列；server-java 启动时扫描 `status=PROCESSING` 且超过超时阈值（10 分钟）的记录，自动标 `FAILED`（`error_code=ORPHANED`），admin 可重试。单实例拓扑下启动时一次性扫描即可，无需心跳/看门狗中间件。

7. **重切竞态：先删后切**：对 READY 文档发起"重新切分"时，先删该文档全部旧 chunk 再进 PROCESSING。PROCESSING 期间该文档无 chunk（运行时检索 SQL 的 `WHERE vector IS NOT NULL` 自然过滤），完成后 READY 新 chunk 可见。代价是重切期间短暂检索真空，但知识库量级小、单文档几 chunk，可忽略。不做"PROCESSING 期间保留旧 chunk 可检索、完成后原子替换"--实现复杂且收益低。

8. **归档连带物理删 chunk，检索 SQL 零改动**：归档（ARCHIVED）时物理删除该文档全部 `knowledge_chunks` 行，`knowledge_documents` 行保留（status=ARCHIVED，元数据 + MinIO 原文作审计痕迹）。这是整个设计最关键的简化决策：运行时检索 SQL 无需加 `WHERE document.status != 'ARCHIVED'` 的 JOIN 过滤，`knowledge_chunks` 行不存在即自然过滤，检索路径与性能零影响。归档可恢复（重新切分写回 chunk，状态回 READY）。

9. **切分策略全确定、零 LLM**：department 由 admin 上传时从标准科室目录选一个，整文档所有 chunk 继承（文档级单一 department）。title = `{文档标题} - 第{n}段`。切分用固定字符滑动窗口（`chunk_size`/`overlap` 契约化），server-java 实现，不引入 LangChain。embedding 输入拼接 `f"{title}。{content}"` 必须与离线脚本 `seed_embeddings.py` 完全一致，保证上传 chunk 与 seed chunk 处于同一 embedding 空间。该拼接格式作为跨栈契约值记录在 `contracts/knowledge-documents.json`。

10. **文档格式：纯文本 + Markdown**：支持 `.txt`/`.md`，零新增 Java 依赖（直接读 UTF-8）。不引入 PDFBox/POI。demo 用 Markdown 医学指南样例演示，效果不输 PDF。若后续需 PDF，切分逻辑与 embedding 链路完全复用，只需加文本提取前置步骤。

## 被否决的方案

- **embedding 仍走离线脚本**：上传闭环只做"上传+切分+存文本（vector NULL）"，embedding 照旧跑 `seed_embeddings.py`。否决：上传与可检索之间留手动脚本缝隙，NULL vector 行被检索 SQL 的 `WHERE vector IS NOT NULL` 过滤成"隐身半态"，"闭环"未真正闭合。
- **切分放 server-py**（复用 LangChain RecursiveCharacterTextSplitter）：否决。chunk 的 `department`/`title` 是业务字段，切分即业务数据诞生时刻，放 server-py 会把业务语义渗进 Agent 层。server-java 切分使 server-py endpoint 退化为纯 embedding 服务，边界最干净。
- **原文即用即弃**（报告解读模型）：否决。报告原文只是"一次解读的输入"无后续管理需求；知识文档是"持续被检索的知识源"，管理其生命周期（切分策略迭代后重建 chunk）是运营刚需。
- **同步三态机 READY/FAILED/ARCHIVED**：否决。大文档切分+embedding 可能超 10s，同步阻塞 HTTP 请求体验差；异步四态虽引入孤儿恢复负担，但 PROCESSING 可见性与重试能力对运营闭环价值更高。
- **归档不删 chunk 加标记过滤**（检索 SQL 加 `WHERE archived=false`）：否决。需改运行时检索 SQL 并加 JOIN，破坏现有检索路径简洁性。物理删 chunk 让检索 SQL 零改动。
- **上传知识进独立表 `knowledge_chunks_uploaded`**：否决。违背一表一义，检索需 UNION，reset 逻辑分叉。
- **chunk 级 department（LLM/规则分类）**：否决。引入不确定性，与"确定性优先"原则摩擦。文档级继承简单且与导诊科室衔接语义一致。
- **embedding 或向量写入迁到 server-java**：维持 ADR-0010 第 8 点否决。本决策的 server-py endpoint 只算向量不写库，不违反此边界。

## 后果

- server-py 首次出现运行时非检索的 embedding endpoint（`/api/agent/knowledge/embeddings`），但它不触碰 `knowledge_chunks` 表，只做模型计算委托，server-py 对业务库只读的不变量不破。
- 本平台首个异步运营链路：引入进程内线程池 + 启动时孤儿扫描，无 MQ/调度中间件。单实例拓扑下可行，多实例时孤儿扫描需协调（当前不涉及）。
- `schema.sql` 新增 `knowledge_documents` 表 + `knowledge_chunks.document_id` 列 + seed 文档行 + 50 条 seed chunk 回填 `document_id`，属 schema 演进票，完成后须跑 `reset_zhiyu.py` 重建 + `verify_zhiyu.py` 验证形状。
- `seed_embeddings.py` 离线脚本保留（仍用于 seed 向量回填），与在线 embedding endpoint 共用 `build_embedding_model` 与 `f"{title}。{content}"` 拼接格式，两者必须保持一致。
- 新增 `contracts/knowledge-documents.json` 承载切分参数、格式白名单、embedding 输入格式、状态/source 枚举，供双栈一致消费。
- 本 ADR 只建立知识文档管理基础设施。ADR-0010 第 5 点要求的完整循证治理（来源、版本、发布日期、适用范围、失效日期、审核人、逐条引用、查看原文、证据不足拒答）仍是独立的未来产品决策，不因本 ADR 而满足。

## 关联

- ADR-0009：维持 server-java 唯一业务写入方、server-py 承载 LLM 与只读知识检索的边界。本 ADR 的 embedding 在线化不违反此边界（模型调用在 server-py，写入在 server-java）。
- ADR-0010：第 8 点否决"embedding 或向量写入迁到 server-java"，本 ADR 守住该否决（见决策第 2 点）。第 5 点的循证问答产品入口要求仍独立于本 ADR。
- ADR-0023：MinIO 旁路持久化模式（拍照分析），本 ADR 的知识文档原文存储复用同模式。
