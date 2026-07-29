"""知识检索工具（ADR-0010）：search_knowledge 作为 @tool 注入 Agent 导诊流程。

工具范式（范式 1）：LLM 自主调用，与既有 tools/business.py 工具架构一致；
关闭知识增强时不注入本工具（LLM 看不到即不检索，裸 LLM）。

分层护栏：tools 不得直接 import app.db/app.services。KnowledgeChunk 召回块
类型定义在此（services.knowledge 反向 import 它，复用同一类型）；检索器经
runner 构造期注入（与 build_business_tools 注入 BusinessCallbackClient 同模式）。
"""

from dataclasses import dataclass
from typing import Any, Protocol

from langchain_core.tools import BaseTool, tool


@dataclass(frozen=True)
class KnowledgeChunk:
    """召回块：格式化文本供 LLM 上下文，附 department 衔接导诊科室推荐。"""

    text: str  # 【标题·科室】正文
    department: str
    score: float


class KnowledgeRetriever(Protocol):
    """检索器最小接口；services.knowledge.PgvectorKnowledgeRetriever 实现它。"""

    async def search(self, query: str) -> list[KnowledgeChunk]: ...


def build_knowledge_tool(retriever: KnowledgeRetriever) -> list[BaseTool]:
    """装配 search_knowledge 工具；检索器构造期注入，运行期由 LLM 自主调用。

    返回 list 以与 build_business_tools 签名对齐，便于 runner 统一拼装工具集。
    """

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

    return [search_knowledge]
