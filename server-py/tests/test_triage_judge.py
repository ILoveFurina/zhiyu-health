"""原 triage judge 已移除；保留本文件覆盖目录白名单解析边界。"""

from app.tools.department import _catalog_hint, _resolve_names

_CANDIDATES = [
    {"id": 5, "name": "皮肤科"},
    {"id": 8, "name": "呼吸内科"},
    {"id": 9, "name": "心血管内科"},
]


def test_names_are_resolved_from_catalog() -> None:
    selected, unknown = _resolve_names(["皮肤科", "呼吸内科"], _CANDIDATES)
    assert [item["id"] for item in selected] == [5, 8]
    assert unknown == []


def test_names_are_trimmed_and_deduplicated() -> None:
    selected, unknown = _resolve_names([" 皮肤科 ", "皮 肤 科"], _CANDIDATES)
    assert [item["id"] for item in selected] == [5]
    assert unknown == []


def test_unknown_names_are_not_guessed() -> None:
    selected, unknown = _resolve_names(["心内科"], _CANDIDATES)
    assert selected == []
    assert unknown == ["心内科"]


def test_catalog_hint_only_uses_trusted_names() -> None:
    assert _catalog_hint(_CANDIDATES) == "可用标准科室为：皮肤科、呼吸内科、心血管内科"
