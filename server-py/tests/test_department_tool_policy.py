import asyncio

from app.services.department_tool_policy import DepartmentToolPolicy


class FakeDirectory:
    def __init__(self, result=None) -> None:
        self.result = result or [
            {"id": 5, "name": "皮肤科"},
            {"id": 8, "name": "呼吸内科"},
        ]
        self.calls = 0

    async def list_departments(self, longitude, latitude):
        self.calls += 1
        return self.result

    async def get_slots(self, department_id, longitude, latitude):
        raise AssertionError("policy must not query slots")


def test_plain_quick_chat_does_not_read_directory_or_force_tool() -> None:
    directory = FakeDirectory()
    plan = asyncio.run(
        DepartmentToolPolicy(directory).resolve(
            [{"role": "user", "content": "你好"}], "triage", None, None
        )
    )

    assert plan.tool_choice is None
    assert plan.standard_departments == ()
    assert directory.calls == 0


def test_explicit_department_booking_forces_slots_tool() -> None:
    directory = FakeDirectory()
    plan = asyncio.run(
        DepartmentToolPolicy(directory).resolve(
            [{"role": "user", "content": "我要挂皮肤科的号"}],
            "triage",
            113.62,
            34.75,
        )
    )

    assert plan.tool_choice == "get_standard_department_slots"
    assert plan.standard_departments == ((5, "皮肤科"), (8, "呼吸内科"))
    assert directory.calls == 1


def test_department_question_forces_options_tool() -> None:
    directory = FakeDirectory()
    plan = asyncio.run(
        DepartmentToolPolicy(directory).resolve(
            [{"role": "user", "content": "胸闷应该挂什么科"}], "triage", None, None
        )
    )

    assert plan.tool_choice == "suggest_standard_departments"
    assert directory.calls == 1


def test_directory_failure_falls_back_to_unforced_main_agent() -> None:
    directory = FakeDirectory("查询标准科室失败")
    plan = asyncio.run(
        DepartmentToolPolicy(directory).resolve(
            [{"role": "user", "content": "我要挂皮肤科"}], "triage", None, None
        )
    )

    assert plan.tool_choice is None
    assert plan.standard_departments == ()
