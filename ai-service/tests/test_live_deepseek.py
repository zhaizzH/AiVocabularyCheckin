"""真实 DeepSeek 路径测试（**默认跳过**，不污染 CI）。

开启方式::

    cd ai-service
    DEEPSEEK_LIVE_TEST=1 ./.venv/Scripts/python.exe -m pytest tests/test_live_deepseek.py -q -s

为什么单独一个文件：
离线用例（FakeLLM）证明「提示词 → 解析 → 收敛 → 校验」这条链路，
但证明不了**供应商真的认我们的请求体**——比如 ``max_tokens`` 走 ``extra_body``、
``thinking: disabled`` 是否被接受、模型名是否还有效。这些只有真调一次才能确认。

跳过条件（任一不满足即 skip）：
1. 环境变量 ``DEEPSEEK_LIVE_TEST=1``；
2. 存在有效 key（从 ``.env`` 读，**不读取也不打印其值**）。
"""

from __future__ import annotations

import os

import httpx
import pytest

from app.core.config import Settings

# 三个接口的自控 deadline（必须严格小于 backend 的 15/30/60s）。
BUDGET_MS = {"/ai/v1/example": 12_000, "/ai/v1/explain": 25_000, "/ai/v1/report": 50_000}

EXPECTED_FIELDS = {
    "/ai/v1/example": {"example", "exampleTranslation", "mnemonic"},
    "/ai/v1/explain": {"explanation", "advice"},
    "/ai/v1/report": {"overview", "weakness", "suggestion"},
}

PAYLOADS = {
    "/ai/v1/example": {"word": "abandon", "translation": "v. 放弃；抛弃", "context": "CET4"},
    "/ai/v1/explain": {
        "word": "abandon",
        "translation": "v. 放弃；抛弃",
        # 与 backend 实际构造的值一致（不含「用户误选了哪个选项」）。
        "wrongContext": "该词在复习中答错，累计错误 3 次",
    },
    "/ai/v1/report": {
        "studyStats": {
            "studyDays": 12,
            "totalLearned": 346,
            "checkInRate": 85,
            "topMistakeWords": ["abandon", "ambiguous", "benefit"],
            "masteryRate": 58,
        }
    },
}


def _live_settings() -> Settings:
    return Settings()


def _skip_reason() -> str | None:
    if os.getenv("DEEPSEEK_LIVE_TEST") != "1":
        return "未设置 DEEPSEEK_LIVE_TEST=1（真实调用默认不跑）"
    try:
        settings = _live_settings()
    except Exception:  # noqa: BLE001 - 配置读取失败一律跳过，不影响离线用例
        return "无法读取配置"
    if not settings.llm_configured:
        return "未配置 DEEPSEEK_API_KEY"
    return None


pytestmark = pytest.mark.skipif(_skip_reason() is not None, reason=_skip_reason() or "")


def _has_chinese(text: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in text)


@pytest.mark.parametrize("path", list(PAYLOADS))
async def test_live_contract_and_latency(path):
    """真实调用：字段严格等于契约 §9、正文非空含中文、耗时在自控预算内。"""
    import time

    from app.main import create_app
    from tests.conftest import running_lifespan

    settings = _live_settings()
    app = create_app(settings=settings)
    async with running_lifespan(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=120) as client:
            started = time.perf_counter()
            response = await client.post(path, json=PAYLOADS[path])
            elapsed_ms = int((time.perf_counter() - started) * 1000)

    assert response.status_code == 200, response.text
    body = response.json()

    # 字段不多不少
    assert set(body) == EXPECTED_FIELDS[path], f"{path} 字段漂移: {sorted(body)}"
    # 全部非空
    assert all(str(value).strip() for value in body.values()), f"{path} 存在空字段: {body}"
    # 面向中文用户，正文应为中文（助记可能中英混排，只要求至少一个字段含中文）
    assert any(_has_chinese(str(value)) for value in body.values()), f"{path} 无中文正文: {body}"
    # 耗时必须在自控预算内，否则 backend 会先超时
    assert elapsed_ms < BUDGET_MS[path], f"{path} 耗时 {elapsed_ms}ms 超出预算 {BUDGET_MS[path]}ms"

    print(f"\n[live] {path} {elapsed_ms} ms / 预算 {BUDGET_MS[path]} ms")
    for key, value in body.items():
        print(f"       {key}: {value}")


async def test_live_report_fast_path_still_contract_shaped():
    """快速路径不调模型，但对外仍必须是完整三段（不能返回空字段）。"""
    from app.main import create_app
    from tests.conftest import running_lifespan

    payload = {
        "studyStats": {
            "studyDays": 5,
            "totalLearned": 50,
            "checkInRate": 100,
            "topMistakeWords": [],
            "masteryRate": 100,
        }
    }
    app = create_app(settings=_live_settings())
    async with running_lifespan(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test", timeout=60) as client:
            response = await client.post("/ai/v1/report", json=payload)

    assert response.status_code == 200, response.text
    body = response.json()
    assert set(body) == {"overview", "weakness", "suggestion"}
    assert all(value.strip() for value in body.values())
