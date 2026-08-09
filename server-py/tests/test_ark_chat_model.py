from langchain_core.messages import AIMessageChunk

from app.core.llm import ArkChatOpenAI


def test_ark_stream_chunk_preserves_reasoning_content() -> None:
    model = ArkChatOpenAI(model="test-model", api_key="test-key", base_url="https://example.test")

    generation = model._convert_chunk_to_generation_chunk(
        {
            "model": "test-model",
            "choices": [
                {
                    "delta": {
                        "role": "assistant",
                        "content": "",
                        "reasoning_content": "先梳理问题。",
                        "encrypted_content": "opaque",
                    },
                    "finish_reason": None,
                }
            ],
        },
        AIMessageChunk,
        None,
    )

    assert generation is not None
    assert generation.message.additional_kwargs["reasoning_content"] == "先梳理问题。"
    assert "encrypted_content" not in generation.message.additional_kwargs


def test_ark_stream_chunk_without_reasoning_keeps_normal_conversion() -> None:
    model = ArkChatOpenAI(model="test-model", api_key="test-key", base_url="https://example.test")

    generation = model._convert_chunk_to_generation_chunk(
        {
            "model": "test-model",
            "choices": [
                {
                    "delta": {"role": "assistant", "content": "正文"},
                    "finish_reason": None,
                }
            ],
        },
        AIMessageChunk,
        None,
    )

    assert generation is not None
    assert generation.message.content == "正文"
    assert "reasoning_content" not in generation.message.additional_kwargs
