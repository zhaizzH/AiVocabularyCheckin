"""ChatOpenAI 工厂。

关键实测结论（见 ``research/01-deepseek-and-deps.md`` §6）：

1. **``max_tokens`` 必须经 ``extra_body`` 传**。``langchain-openai==1.7.2``
   会把构造参数 ``max_tokens=`` 改名为 ``max_completion_tokens`` 发出去，
   而 DeepSeek 只认 ``max_tokens``。走 ``extra_body`` 才能让最终请求体
   出现供应商认识的键。
2. ``bind(response_format={"type": "json_object"})`` 会把 ``response_format``
   放在**最终请求体顶层**（不是 ``extra_body`` 包装层里）。
3. ``extra_body={"thinking": ...}`` 合并后同样出现在**顶层**。
4. 不传 ``temperature``：thinking 模式不支持该参数（设了也静默无效），
   关掉 thinking 后也不传，避免「以为调了其实没调」的假象。
5. HTTP client 由 lifespan 创建并注入 ``http_async_client``；
   实测 ``ChatOpenAI(timeout=)`` 会覆盖共享 client 自身的 timeout，
   因此单次尝试上限完全由 ``llm_attempt_timeout_s`` 决定。
"""

from __future__ import annotations

import httpx
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.runnables import Runnable
from langchain_openai import ChatOpenAI

from app.core.config import Settings
from app.core.errors import not_configured_error

# 各功能的输出 token 上限（JSON 模式下必须设，否则截断导致解析失败）。
MAX_TOKENS_EXAMPLE = 512
MAX_TOKENS_EXPLAIN = 1024
MAX_TOKENS_REPORT_ANALYZE = 512
MAX_TOKENS_REPORT_COMPOSE = 1536


def build_chat_model(
    settings: Settings,
    *,
    http_async_client: httpx.AsyncClient,
    max_tokens: int,
) -> BaseChatModel:
    """构造单个模型实例（未绑定 JSON mode）。

    缺 key 时抛 503，而不是让服务启动失败：启动即崩会让 backend
    只看到「连接被拒」，与「key 未配置」无法区分，可观测性更差。
    """
    if not settings.llm_configured or settings.deepseek_api_key is None:
        raise not_configured_error()

    return ChatOpenAI(
        model=settings.deepseek_model,
        base_url=settings.deepseek_base_url,
        api_key=settings.deepseek_api_key.get_secret_value(),
        max_retries=settings.deepseek_max_retries,
        timeout=settings.llm_attempt_timeout_s,
        http_async_client=http_async_client,
        extra_body={
            # JSON 输出与 thinking 有已知冲突（会返回空 content），一律关闭。
            "thinking": {"type": "disabled"},
            # 必须放这里，见模块 docstring 第 1 条。
            "max_tokens": max_tokens,
        },
    )


def build_json_runnable(
    settings: Settings,
    *,
    http_async_client: httpx.AsyncClient,
    max_tokens: int,
) -> Runnable:
    """构造绑定了 JSON 输出模式的 Runnable。

    ``response_format`` 是 OpenAI Chat Completions 的标准顶层参数，
    因此用 ``bind`` 而不是塞进 DeepSeek 专属的 ``extra_body``。
    """
    model = build_chat_model(
        settings, http_async_client=http_async_client, max_tokens=max_tokens
    )
    return model.bind(response_format={"type": "json_object"})
