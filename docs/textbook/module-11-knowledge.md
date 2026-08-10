# 模块11：医学知识图谱与检索

## 业务概述

本模块为 C 端对话 Agent 提供“知识增强”能力：导诊/预问诊场景下，LLM 回答前可检索医学知识，使回答更准确并可衔接科室推荐。检索有双路：pgvector 向量 Top-K（rag 态，召回 `knowledge_chunks` 知识块）与 Neo4j 知识图谱一跳遍历（graph 态，扩展症状-疾病-科室-药品-禁忌邻接）。两条路都是只读、失败静默降级走裸 LLM。此外，同一份 Neo4j 图谱还支撑 B 端可视化页（全图投影 + 节点详情）和 server-java 处方禁忌规则的事实读取。

## 业务流程

C 端知识增强（以导诊为例）：

1. 患者在小程序发起导诊对话，请求到达 server-java 对话入口，server-java 先跑确定性红线规则，再按当前知识源开关（Redis 单键，ADR-0021）把 `knowledge_source`（rag/graph/none）透传给 server-py。
2. server-py 编排层用 `KnowledgeSourcePolicy.resolve` 裁决实际知识源：请求值非法或对应只读能力未装配（pg 未配置 / Neo4j 驱动缺失）时降级为 `none`；能力缺失时先发一个 `knowledge` 元事件（degraded/unavailable）告知端侧。
3. runner 按 `(effort, knowledge_source, scenario)` 编译 LangGraph：rag 态只注入 `search_knowledge` 工具，graph 态只注入 `traverse_graph` 工具（互斥），none 态不注入知识工具（LLM 看不到即不检索）。
4. LLM 在回答前自主调用知识工具：rag 路把 query 做 embedding，在 `knowledge_chunks` 上按 cosine 相似度阈值过滤取 Top-3；graph 路按实体名（含别名）匹配五类节点并沿出/入边各扩展一跳。
5. 召回结果以结构化纯文本（rag）或邻接明细 + 自然语言 summary（graph）回到 LLM 上下文，LLM 生成带科室推荐的回答，token 经 SSE 逐跳透传回小程序，出口兜底注入“仅供参考，不替代医生诊断”。
6. 任一检索环节失败（embedding 调用、DB 连接、Cypher 执行）都静默返回空，对话不被阻断，直接走裸 LLM。

B 端图谱可视化：

1. admin 浏览器请求 `/api/b/knowledge/graph`，经 server-java `AdminInterceptor` 鉴权（仅 admin 角色）。
2. server-java `KnowledgeGraphController` 零业务逻辑，经 `AgentClient.fetchGraphProjection()` 转调 server-py `/api/knowledge/graph`（携带 Agent 回调鉴权头）。
3. server-py `Neo4jGraphProjector` 用只读会话跑投影 Cypher，返回全图最小拓扑骨架 `{nodes, edges}`（不含节点属性）。
4. 前端 G6 力导向图渲染五类节点六类边；点击节点时再走 `/api/b/knowledge/graph/node` 另取详情，抽屉展示属性。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| server-py services | pgvector 只读 Top-K 检索 + 召回块格式化 | `server-py/app/services/knowledge.py` |
| server-py services | Neo4j 一跳遍历 + B 端图谱投影/节点详情 | `server-py/app/services/graph.py` |
| server-py services | 知识源选择与降级事件（rag/graph/none） | `server-py/app/services/chat_knowledge.py` |
| server-py tools | `search_knowledge` 工具定义 + KnowledgeChunk/检索器 Protocol | `server-py/app/tools/knowledge.py` |
| server-py tools | `traverse_graph` 工具定义 + GraphNeighbor/遍历器 Protocol | `server-py/app/tools/graph.py` |
| server-py agent | 工具集按 knowledge_source 动态拼装（互斥注入） | `server-py/app/agent/runner.py` |
| server-py db | Neo4j 驱动 + pg 只读检索连接装配、向量维度钉契约 | `server-py/app/db/clients.py` |
| server-py core | 火山方舟 OpenAIEmbeddings 唯一构建点 | `server-py/app/core/embeddings.py` |
| server-py api | B 端图谱投影/节点详情只读 HTTP 接口 | `server-py/app/api/knowledge.py` |
| server-py bootstrap | 生产装配：检索器/遍历器/投影器构建并注入 | `server-py/app/bootstrap.py` |
| server-java controller | B 端图谱只读入口，鉴权后转调 server-py | `server-java/src/main/java/com/zhiyu/health/controller/staff/knowledge/KnowledgeGraphController.java` |
| server-java agentclient | 转调 server-py `/api/knowledge/graph(/node)` | `server-java/src/main/java/com/zhiyu/health/agentclient/KnowledgeGraphAgentApi.java` |
| server-java rule | 处方禁忌规则的 Neo4j 只读事实适配器 | `server-java/src/main/java/com/zhiyu/health/rule/Neo4jContraindicationFactRepository.java` |
| admin service | 图谱投影/节点详情请求封装与 TS 类型 | `admin/src/services/knowledgeGraph.ts` |
| admin page | G6 力导向图可视化 + 节点详情抽屉 | `admin/src/pages/KnowledgeGraph/index.tsx` |
| 契约 | 知识源二态、SSE 元事件、检索参数单一事实源 | `contracts/knowledge.json` |

## 核心代码走读

### 11.1 知识源选择与降级裁决

`KnowledgeSourcePolicy` 是 rag/graph/none 三态的唯一裁决点，允许值与场景默认值全部来自契约而非硬编码。

`server-py/app/services/chat_knowledge.py:17-25`：

```python
    def resolve(self, requested: str | None, scenario: Scenario) -> str:
        source = requested or self._contract.default_by_scenario[scenario]
        if source not in self._contract.knowledge_sources:
            return self._contract.none_source
        if source == "rag" and not self._rag_available:
            return self._contract.none_source
        if source == "graph" and not self._graph_available:
            return self._contract.none_source
        return source
```

对应契约 `contracts/knowledge.json`：知识源允许值是 `["rag", "graph"]`，`none_source` 为 `"none"`，场景默认值 triage/preconsultation 为 rag、interpretation 为 none，`search_top_k=3`、`similarity_threshold=0.3`、`embedding_dimension=2048`。注意裁决只依赖“能力是否已装配”这一构造期事实——`resolve` 不做运行时探测；能力缺失时的降级事件由 `degraded_event`（同文件 27-33 行）在 runner 前产生，graph 缺失发 `unavailable`、rag 缺失发 `degraded`，经 SSE `knowledge` 元事件通知端侧。

### 11.2 pgvector 向量 Top-K 检索（rag 路）

`PgvectorKnowledgeRetriever.search` 是 rag 路的全部运行时逻辑：embed query → cosine 距离排序 → 阈值过滤 + Top-K。

`server-py/app/services/knowledge.py:46-72`：

```python
                cur = await conn.execute(
                    # cosine 距离 <=> 越小越相似；阈值过滤 + Top-K
                    # vector 列可能为 NULL（向量未回填），过滤掉只取已向量化行
                    """
                    SELECT title, department, content,
                           1 - (vector <=> %s::vector) AS score
                    FROM knowledge_chunks
                    WHERE vector IS NOT NULL
                      AND 1 - (vector <=> %s::vector) >= %s
                    ORDER BY vector <=> %s::vector
                    LIMIT %s
                    """,
                    (query_vec, query_vec, self._threshold, query_vec, self._top_k),
                )
                rows = await cur.fetchall()
            finally:
                await conn.close()
        except Exception:  # noqa: BLE001 - 降级：任何检索异常都不阻断对话
            return []
```

三个值得讲的细节：一是 `<=>` 是 pgvector 的 cosine 距离算子，`1 - 距离` 还原为相似度后再套契约阈值；二是 `vector IS NOT NULL` 过滤掉向量未回填的行，避免离线 seed 与在线检索口径不一致；三是整个 `try` 覆盖 embedding 调用、连接、SQL 执行三段，任何异常都返回空列表——检索失败不阻断对话、也不向用户暴露错误。召回块格式化为 `【标题·科室】正文`（同文件 `_format`，26-27 行），科室字段衔接导诊推荐。

### 11.3 Neo4j 一跳遍历与图谱投影（graph 路 + B 端）

graph 路的核心是一跳扩展 Cypher：按实体名（含别名）匹配种子节点，沿出边和入边各扩展一跳。

`server-py/app/services/graph.py:33-48`：

```python
_TRAVERSE_CYPHER = """
UNWIND $entities AS entity
MATCH (n)
WHERE (n:Symptom OR n:Disease OR n:Department OR n:Medication OR n:Contraindication)
  AND (n.name = entity OR entity IN n.aliases)
OPTIONAL MATCH (n)-[out_rel]->(neighbor)
WHERE neighbor IS NOT NULL
OPTIONAL MATCH (n)<-[in_rel]-(in_neighbor)
WHERE in_neighbor IS NOT NULL
RETURN neighbor.name AS neighbor_name,
       labels(neighbor)[0] AS neighbor_type,
       type(out_rel) AS out_relation,
       in_neighbor.name AS in_neighbor_name,
       labels(in_neighbor)[0] AS in_neighbor_type,
       type(in_rel) AS in_relation
"""
```

别名匹配 `entity IN n.aliases` 是关键设计：把“叫法不同”这个最常见的空召回源在 seed 层消解（节点带 aliases 属性），不引入运行时模糊匹配。三条 Cypher（遍历 `_TRAVERSE_CYPHER`、B 端投影 `_PROJECTION_CYPHER`、节点详情 `_DETAIL_CYPHER`）共用 `_read` 辅助（76-87 行）：统一开 `default_access_mode="READ"` 的只读会话，捕获 `Neo4jError/OSError` 返回 None 实现降级，避免三处复制粘贴同样的异常处理。`Neo4jGraphProjector.projection`（153-181 行）把记录组装成 `{nodes:[{id,label,group}], edges:[{source,target,type}]}` 最小拓扑骨架，节点 id 用 `{label}:{natural_key}` 复合形式，属性不塞进投影。

### 11.4 工具调用：search_knowledge 与 traverse_graph（@tool、Protocol 反向注入、互斥策略）

**工具定义点**：两个知识工具都在 `server-py/app/tools/` 下用 `@tool` 装饰器定义。`search_knowledge` 在 `server-py/app/tools/knowledge.py:38-53`：

```python
    @tool
    async def search_knowledge(query: str) -> dict[str, Any]:
        """检索医学知识库，获取与用户症状相关的健康知识（症状、病因、建议科室、就医提示）。

        用于导诊回答前检索相关知识，使回答更准确、可衔接科室推荐。
        检索失败或无相关内容时返回空，由你基于自身知识回答。
        """
        chunks = await retriever.search(query)
        return {
            "query": query,
            "chunks": [c.text for c in chunks],
            "departments": [c.department for c in chunks],
            "count": len(chunks),
        }
```

`traverse_graph` 在 `server-py/app/tools/graph.py:58-94`，入参为实体名列表，内部调用注入的 `traverser.traverse(entities)`，对邻接结果去重后返回结构化 `neighbors` + 自然语言 `summary` + `count`；`seed_entities` 是预留参数，为未来 RAG+图谱融合管道留口子。

**Protocol 反向注入（分层护栏）**：tools 层不得 import `app.services`/`app.db`，但工具又必须调用检索器。解法是“类型归 tools、实现归 services、运行期注入”——`KnowledgeRetriever` Protocol 定义在 `server-py/app/tools/knowledge.py:26-29`：

```python
class KnowledgeRetriever(Protocol):
    """检索器最小接口；services.knowledge.PgvectorKnowledgeRetriever 实现它。"""

    async def search(self, query: str) -> list[KnowledgeChunk]: ...
```

`GraphTraverser` Protocol 对称地定义在 `tools/graph.py:31-34`。services 层的 `PgvectorKnowledgeRetriever`/`Neo4jGraphTraverser` 实现这些 Protocol，并反向 import tools 层的 `KnowledgeChunk`/`GraphNeighbor` 类型复用；`build_knowledge_tool(retriever)` / `build_graph_tool(traverser)` 以构造期闭包注入的方式把实现缝进工具。同理 `tools/callback.py` 的业务工具用 HMAC 鉴权回调 server-java——但注意两个知识工具**不回调** server-java，它们只读知识存储（pgvector/Neo4j），这正是硬约束“知识检索可直连、业务写入必须回调”的分界线。

**注册进 LangGraph 与互斥策略**：`server-py/app/agent/runner.py:126-144`：

```python
        self._knowledge_tools = (
            build_knowledge_tool(knowledge_retriever) if knowledge_retriever is not None else []
        )
        # graph 工具与 search_knowledge 互斥：graph 态只注入 traverse_graph（grilling 决策 2）
        self._graph_tools = build_graph_tool(graph_traverser) if graph_traverser is not None else []
        # 缓存键 (effort, knowledge_source, scenario)：工具集与提示词随两者变化
        self._graphs: dict[tuple[str, str, str], CompiledStateGraph[Any, Any, Any, Any]] = {}

    def _tools_for(self, knowledge_source: str, scenario: str) -> list[BaseTool]:
        # 工具隔离：预问诊场景不暴露任何业务工具（医生推荐/号源/挂号），
        # 隔离由编排代码保证而非提示词；知识工具仍按 knowledge_source 注入。
        # rag 态注入 search_knowledge；graph 态注入 traverse_graph（互斥）；
        # none/其他不注入（LLM 看不到即不检索）
        base = [] if scenario == _PRECONSULT_SCENARIO else self._base_tools
        if knowledge_source == "rag" and self._knowledge_tools:
            return [*base, *self._knowledge_tools]
        if knowledge_source == "graph" and self._graph_tools:
            return [*base, *self._graph_tools]
        return list(base)
```

互斥由编排代码保证：同一请求的工具集里只有一个知识工具，且 none 态干脆不注入（LLM 看不到工具即不会检索，无需靠提示词约束）。编译图按 `(effort, knowledge_source, scenario)` 缓存（`_graph`，146-162 行），切换知识源即换一张图。生产装配在 `server-py/app/bootstrap.py:38-60`：`build_knowledge_retriever(settings)` 与 `build_graph_traverser(clients)` 任一为 None 即代表该路能力缺失，runner 相应工具集为空。

### 11.5 B 端图谱可视化链路（鉴权转调 + G6 渲染）

B 端不直连 server-py 或 Neo4j，全部经 server-java 鉴权透传。`server-java/src/main/java/com/zhiyu/health/controller/staff/knowledge/KnowledgeGraphController.java:18-35`：

```java
@RestController
@RequestMapping("/api/b/knowledge")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final AgentClient agentClient;

    /** 返回全图最小拓扑骨架 {nodes, edges}（ADR-0013 决策 6）。 */
    @GetMapping("/graph")
    public GraphProjection graph() {
        return agentClient.fetchGraphProjection();
    }

    /** 点击节点取详情：返回节点类型与全部属性（grilling 决策 6：属性不塞进投影）。 */
    @GetMapping("/graph/node")
    public JsonNode nodeDetail(@RequestParam("node_id") String nodeId) {
        return agentClient.fetchGraphNodeDetail(nodeId);
    }
}
```

`AgentClient.fetchGraphProjection()`（`agentclient/AgentClient.java:63`）委托 `KnowledgeGraphAgentApi`（同包 `KnowledgeGraphAgentApi.java:21/36`）带回调鉴权头请求 server-py `/api/knowledge/graph(/node)`；server-py 侧 `api/knowledge.py:15-40` 用 `AgentCallbackAuth` 校验，从 `app.state.graph_projector` 取投影器，未配置时投影接口降级返回空图而节点详情接口抛 503。admin 前端 `admin/src/services/knowledgeGraph.ts:38-47` 封装两个请求，`admin/src/pages/KnowledgeGraph/index.tsx` 用 G6 v5 `d3-force` 力导向渲染：五类节点按 `GROUP_COLORS` 着色（14-20 行），边按关系类型染色并悬停显示中文标签，仿真收敛后把坐标缓存进 sessionStorage（244-259 行），重进页面预填坐标并令 `alpha == alphaMin` 跳过约 300 次力仿真迭代。

### 11.6 server-java 侧的图谱只读消费：处方禁忌事实

同一份 Neo4j 图谱还被 server-java 规则引擎只读消费——这是“Neo4j 只存医学知识、双栈都只读”的另一处体现。`server-java/src/main/java/com/zhiyu/health/rule/Neo4jContraindicationFactRepository.java:44-67`：

```java
    @Override
    public ContraindicationFacts load(List<Long> medicationIds) {
        SessionConfig readOnly =
                SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build();
        try (Session session = driver.session(readOnly)) {
            List<Record> medicationRecords =
                    session.executeRead(tx -> tx.run(MEDICATION_FACTS, parameters("medicationIds", medicationIds))
                            .list());
            List<MedicationContraindicationFact> medications = medicationRecords.stream()
                    .filter(record -> record.get("found").asBoolean())
                    .map(record -> new MedicationContraindicationFact(
                            record.get("medicationId").asLong(),
                            record.get("ingredients").asList(value -> value.asString()),
                            record.get("allergyTerms").asList(value -> value.asString())))
                    .toList();
            List<MedicationInteractionFact> interactions =
                    session.executeRead(tx -> tx.run(INTERACTIONS, parameters("medicationIds", medicationIds))
                            .list(record -> toInteraction(record)));
            boolean complete = medications.size() == medicationIds.size()
                    && medications.stream()
                            .allMatch(medication -> !medication.ingredients().isEmpty());
            return new ContraindicationFacts(medications, interactions, complete);
        }
    }
```

适配器只负责取事实：固定 READ session，两条 Cypher 分别取药品成分/过敏原（沿 `CONTRAINDICATED_FOR` 边）和药品两两相互作用（`INTERACTS_WITH` 边，`left < right` 去重对称对）；规则判断（过敏命中、相互作用拦截、事实不全 `complete=false` 的处置）全部在上层规则引擎完成，本类不承担任何决策。这与 server-py 侧 `_read` 辅助的只读纪律完全对称。

## 契约与 ADR

- `contracts/knowledge.json`：知识源二态（rag/graph）+ none、场景默认值、SSE `knowledge` 元事件状态（ok/degraded/unavailable）、向量维度 2048、Top-K=3、相似度阈值 0.3 的单一事实源。
- `docs/adr/0006-neo4j-knowledge-graph.md`（知识图谱：真图数据库 Neo4j，但严格限定边界）：Neo4j 只存症状/疾病/科室/药品/禁忌等医学知识，业务实体零双写。
- `docs/adr/0010-rag-knowledge-retrieval.md`（RAG 知识检索只用于受控证据问答与技术演示）：rag 路定位、只读检索与降级纪律；注意与下面的跨栈契约 ADR 同号不同题。
- `docs/adr/0010-cross-stack-contracts.md`（跨栈契约：contracts/ JSON 单一事实源 + 双栈启动加载）：知识源取值、检索参数必须经 contracts/ 双栈共享，启动加载。
- `docs/adr/0013-graph-read-paths-split.md`（图谱只读访问的双接口分离）：LLM 工具（traverse_graph）与 B 端投影接口分离，投影返回最小拓扑骨架、属性点击另取。
- `docs/adr/0021-knowledge-source-live-toggle-redis-key.md`（知识源现场切换：Redis 单键 + 逐请求补位）：knowledge_source 可由 Redis 键现场切换，逐请求生效。

## 讲解提示

- **双路检索取舍**：rag（向量 Top-K）擅长非结构化长文本语义匹配（“知识块里哪几段和用户描述像”），但只返回片段、不保证关系准确；graph（图谱一跳）返回的是结构化、可解释的关系（症状→疾病→科室），召回依赖实体名命中。两者互斥注入而非融合，是 demo 期的刻意简化——`traverse_graph` 的 `seed_entities` 预留参数明示了未来“先 RAG 抽实体、再图谱扩展”的融合方向。
- **分层护栏为什么反向 import**：常见提问“为什么类型定义在 tools 而实现在 services？”——因为分层规则禁止 tools import services/db，而工具签名又必须引用 `KnowledgeChunk`/`GraphNeighbor`；把类型与 Protocol 归 tools、让 services 反向 import 并实现 Protocol，再用构造期注入（闭包）缝合，两层的依赖方向始终指向 tools，编译期可静态检查。
- **降级纪律是全模块的一致性设计**：检索失败、空召回、能力未装配三种情况分别对应“返回空列表走裸 LLM”“LLM 基于自身知识回答”“none 态 + SSE 元事件告知端侧”。可以让学生对比三处代码（services/knowledge.py 的 `except Exception: return []`、graph.py 的 `_read` 返回 None、chat_knowledge.py 的 `resolve`），指出它们分别发生在运行期、连接期、构造期。
- **常见提问“为什么 B 端不直连 Neo4j？”**：鉴权与审计统一在 server-java 入口执行（硬约束），admin 浏览器只认 `/api/b/**`；server-py 的 `/api/knowledge/graph` 只接受带 Agent 回调鉴权头的转调请求。让学生顺着 admin service → KnowledgeGraphController → KnowledgeGraphAgentApi → api/knowledge.py 走一遍四跳链路。

> 返回目录：[docs/textbook/README.md](./README.md)
