from langchain_core.messages import AIMessage, ToolCall

from app.agent.events import model_outputs


def test_tool_argument_continuation_without_name_does_not_emit_phantom_start() -> None:
    message = AIMessage(
        content="",
        tool_calls=[ToolCall(name="", args={}, id="call-1")],
    )

    assert model_outputs(message, "disabled") == []
