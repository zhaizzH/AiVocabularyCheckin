"""阶段 7 验证脚本：真实调用三个 AI 接口并记录耗时。

用法（key 已在 ai-service/.env 中，脚本**不读取也不打印** key）::

    cd ai-service
    ./.venv/Scripts/python.exe scripts/verify_live.py

判据：
1. HTTP 200 且返回体字段**严格等于**契约 §9（不多不少）；
2. 三段正文非空，且包含中文（防止模型返回英文占位）;
3. 实测耗时低于该接口的自控 deadline（12 / 25 / 50s）。

脚本失败时会以非零码退出，便于 CI 或人工一眼判断。
"""

from __future__ import annotations

import asyncio
import json
import sys
import time

import httpx

BASE_URL = "http://127.0.0.1:8000"

# 契约 §9 的字段集合：多一个字段或少一个字段都算违约。
EXPECTED_FIELDS = {
    "/ai/v1/example": {"example", "exampleTranslation", "mnemonic"},
    "/ai/v1/explain": {"explanation", "advice"},
    "/ai/v1/report": {"overview", "weakness", "suggestion"},
}

DEADLINES_MS = {
    "/ai/v1/example": 12_000,
    "/ai/v1/explain": 25_000,
    "/ai/v1/report": 50_000,
}

PAYLOADS = {
    "/ai/v1/example": {
        "word": "abandon",
        "translation": "v. 放弃；抛弃",
        "context": "CET4",
    },
    "/ai/v1/explain": {
        "word": "abandon",
        "translation": "v. 放弃；抛弃",
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


def _has_chinese(text: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in text)


async def call_one(client: httpx.AsyncClient, path: str) -> bool:
    started = time.perf_counter()
    try:
        response = await client.post(path, json=PAYLOADS[path])
    except httpx.HTTPError as exc:
        print(f"[FAIL] {path} 请求异常: {type(exc).__name__}")
        return False
    elapsed_ms = int((time.perf_counter() - started) * 1000)

    if response.status_code != 200:
        print(f"[FAIL] {path} HTTP {response.status_code}: {response.text[:200]}")
        return False

    try:
        body = response.json()
    except json.JSONDecodeError:
        print(f"[FAIL] {path} 响应不是 JSON: {response.text[:200]}")
        return False

    expected = EXPECTED_FIELDS[path]
    actual = set(body)
    if actual != expected:
        print(f"[FAIL] {path} 字段不符 期望={sorted(expected)} 实际={sorted(actual)}")
        return False

    blank = [key for key, value in body.items() if not str(value).strip()]
    if blank:
        print(f"[FAIL] {path} 存在空字段: {blank}")
        return False

    non_chinese = [key for key, value in body.items() if not _has_chinese(str(value))]
    if non_chinese:
        print(f"[WARN] {path} 这些字段不含中文: {non_chinese}")

    budget = DEADLINES_MS[path]
    verdict = "OK  " if elapsed_ms < budget else "SLOW"
    print(f"[{verdict}] {path:16s} {elapsed_ms:6d} ms  (自控 deadline {budget} ms)")
    print(json.dumps(body, ensure_ascii=False, indent=2))
    return elapsed_ms < budget


async def main() -> int:
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=120.0) as client:
        try:
            health = await client.get("/health")
        except httpx.HTTPError as exc:
            print(f"[FAIL] 连不上 {BASE_URL}: {type(exc).__name__}")
            print("       先启动服务: ./.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000")
            return 2
        print(f"[health] {health.status_code} {health.text}")
        if not health.json().get("llmConfigured"):
            print("[FAIL] llmConfigured=false：ai-service/.env 里没有有效 DEEPSEEK_API_KEY")
            return 2

        results = []
        for path in PAYLOADS:
            results.append(await call_one(client, path))

    ok = all(results)
    print("\n=== 总体:", "PASS" if ok else "FAIL", "===")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
