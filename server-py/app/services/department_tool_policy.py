"""科室工具首轮路由：用零模型调用识别明确挂号/导诊请求。"""

from dataclasses import dataclass

from app.services.directory import DepartmentDirectory

_BOOKING_MARKERS = ("挂号", "预约", "号源", "挂")
_DEPARTMENT_QUESTION_MARKERS = (
    "挂什么科",
    "看什么科",
    "该挂哪",
    "该看哪",
    "哪个科",
    "哪一科",
    "什么科室",
)
_DEPARTMENT_SLOTS_TOOL = "get_standard_department_slots"
_DEPARTMENT_OPTIONS_TOOL = "suggest_standard_departments"


@dataclass(frozen=True)
class DepartmentToolPlan:
    """本轮首个模型调用的受控工具选择与可信标准科室目录。"""

    tool_choice: str | None = None
    standard_departments: tuple[tuple[int, str], ...] = ()


class DepartmentToolPolicy:
    """只在明显需要科室卡时约束主 Agent，不增加第二次 LLM 判定。"""

    def __init__(self, directory: DepartmentDirectory | None) -> None:
        self._directory = directory

    async def resolve(
        self,
        messages: list[dict[str, str]],
        scenario: str,
        longitude: float | None,
        latitude: float | None,
    ) -> DepartmentToolPlan:
        if self._directory is None or scenario != "triage" or not messages:
            return DepartmentToolPlan()

        user_messages = [item["content"] for item in messages if item["role"] == "user"]
        if not user_messages:
            return DepartmentToolPlan()
        latest = user_messages[-1]
        booking_intent = any(marker in latest for marker in _BOOKING_MARKERS)
        department_question = any(marker in latest for marker in _DEPARTMENT_QUESTION_MARKERS)
        continuing_triage = len(user_messages) > 1 and any(
            marker in text for text in user_messages[:-1] for marker in _DEPARTMENT_QUESTION_MARKERS
        )
        if not booking_intent and not department_question and not continuing_triage:
            return DepartmentToolPlan()

        candidates = await self._directory.list_departments(longitude, latitude)
        if isinstance(candidates, str) or not candidates:
            return DepartmentToolPlan()
        catalog = tuple((item["id"], item["name"]) for item in candidates)

        normalized_latest = _normalized(latest)
        exact_department = next(
            (name for _, name in catalog if _normalized(name) in normalized_latest), None
        )
        if booking_intent and exact_department is not None:
            return DepartmentToolPlan(_DEPARTMENT_SLOTS_TOOL, catalog)
        if department_question or continuing_triage:
            return DepartmentToolPlan(_DEPARTMENT_OPTIONS_TOOL, catalog)
        return DepartmentToolPlan()


def _normalized(value: str) -> str:
    return "".join(value.split()).casefold()
