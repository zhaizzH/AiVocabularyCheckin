"""稳定错误常量与 HTTPException 构造器。

为什么 detail 用**纯 ASCII 小写下划线串**：
1. 契约要求「稳定字符串」——backend 只把它当日志线索，不解析内容；
2. 规避 GBK 终端下的 ``UnicodeEncodeError``（本项目在 Windows GBK 控制台踩过）；
3. 便于 grep 定位。

为什么 detail 不是 ``{"code": ..., "message": ...}`` 结构：
FastAPI 的 ``detail`` 会原样作为响应体 JSON，而契约未定义 ai-service 的错误体结构，
backend 只按异常类型映射（超时 5001 / 其它 5002），故保持最小惊讶的原生字符串形状。
"""

from __future__ import annotations

from http import HTTPStatus

from fastapi import HTTPException

# ---- 稳定 detail 常量（对外承诺，改动即破坏可观测性）----
ERR_LLM_TIMEOUT = "ai_service_timeout"
ERR_LLM_FAILED = "ai_service_llm_failed"
ERR_LLM_NOT_CONFIGURED = "ai_service_llm_not_configured"
ERR_BAD_MODEL_OUTPUT = "ai_service_bad_model_output"
ERR_EMPTY_RESULT = "ai_service_empty_result"
ERR_INTERNAL_STATE = "ai_service_internal_state"


def timeout_error() -> HTTPException:
    """模型调用超时（含请求级总 deadline 到期）。"""
    return HTTPException(status_code=HTTPStatus.GATEWAY_TIMEOUT, detail=ERR_LLM_TIMEOUT)


def llm_failed_error() -> HTTPException:
    """模型调用失败：连接错误、5xx、认证失败等。"""
    return HTTPException(status_code=HTTPStatus.BAD_GATEWAY, detail=ERR_LLM_FAILED)


def not_configured_error() -> HTTPException:
    """未配置 API key。调用期报错，而不是启动崩。"""
    return HTTPException(
        status_code=HTTPStatus.SERVICE_UNAVAILABLE, detail=ERR_LLM_NOT_CONFIGURED
    )


def bad_output_error() -> HTTPException:
    """模型输出无法解析成契约要求的 JSON。"""
    return HTTPException(status_code=HTTPStatus.BAD_GATEWAY, detail=ERR_BAD_MODEL_OUTPUT)


def empty_result_error() -> HTTPException:
    """解析成功但关键字段为空（空成功对用户无意义，必须判失败）。"""
    return HTTPException(status_code=HTTPStatus.BAD_GATEWAY, detail=ERR_EMPTY_RESULT)


def internal_state_error() -> HTTPException:
    """编排内部状态缺失（如 report 的 compose 拿不到 analysis）。

    这类错误说明是我们的 bug 而非模型问题，但对外仍必须是非 2xx，
    以免 backend 把半成品当成功返回给用户。
    """
    return HTTPException(
        status_code=HTTPStatus.INTERNAL_SERVER_ERROR, detail=ERR_INTERNAL_STATE
    )
