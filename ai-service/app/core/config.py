"""配置单一入口（pydantic-settings）。

设计约束：

1. key 用 ``SecretStr`` 承载，``repr(settings)`` 不会泄露明文；
2. key **允许缺失**——服务要能启动、由 ``/health`` 反映配置状态，
   缺 key 只在**调用时**报稳定的 503，而不是启动就崩
   （启动即崩会让 backend 只看到「连接被拒」，与「key 未配置」无法区分）；
3. thinking 模式不支持开启：JSON 输出与 thinking 有已知冲突（会返回空 content），
   因此 ``DEEPSEEK_THINKING_ENABLED=true`` 属于危险误配，直接拒绝启动。
"""

from __future__ import annotations

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """全部可配置项的唯一定义处。代码中不出现任何字面量密钥。"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        # 忽略未知环境变量：部署环境常混杂其他服务的变量，
        # 因一个无关变量而拒绝启动是过度严格。
        extra="ignore",
        case_sensitive=False,
    )

    # ---- 供应商 ----
    # 无默认值，但**允许缺失**：见模块 docstring 第 2 条。
    deepseek_api_key: SecretStr | None = None
    deepseek_base_url: str = "https://api.deepseek.com"
    # deepseek-chat / deepseek-reasoner 已于 2026-07-24 退役，故默认用新 id。
    deepseek_model: str = "deepseek-flash"
    # 固定 false；true 会在校验阶段被拒绝（见下方 validator）。
    deepseek_thinking_enabled: bool = False
    deepseek_max_retries: int = Field(default=2, ge=0, le=5)

    # ---- 超时预算 ----
    # 请求级总 deadline，必须小于 backend 侧的 15/30/60s：
    # backend 一旦先超时就断开连接，我们后续的重试与解析全是白做工。
    example_timeout_s: float = Field(default=12.0, gt=0, lt=15)
    explain_timeout_s: float = Field(default=25.0, gt=0, lt=30)
    report_timeout_s: float = Field(default=50.0, gt=0, lt=60)
    # 单次模型 HTTP 尝试的上限（不是请求总预算）。
    # 取 5s 而非 10s：example 总预算只有 12s，单次 10s 会让重试永远来不及发生。
    llm_attempt_timeout_s: float = Field(default=5.0, gt=0, lt=15)

    # ---- 日志 ----
    log_level: str = "INFO"

    @field_validator("deepseek_thinking_enabled")
    @classmethod
    def _reject_thinking_enabled(cls, value: bool) -> bool:
        """thinking 模式与 JSON 输出冲突，且会顶穿 15s 预算，故不允许开启。"""
        if value:
            raise ValueError(
                "DEEPSEEK_THINKING_ENABLED=true is not supported: "
                "JSON output mode conflicts with DeepSeek thinking mode "
                "(responses come back with empty content). Keep it false."
            )
        return value

    @property
    def llm_configured(self) -> bool:
        """是否已配置 key。只暴露布尔，供 /health 使用，绝不返回 key 本身。"""
        return self.deepseek_api_key is not None and bool(
            self.deepseek_api_key.get_secret_value().strip()
        )


def get_settings() -> Settings:
    """读取配置。

    刻意**不加** ``lru_cache``：Settings 一旦被缓存，测试就无法用 monkeypatch
    改环境变量重新加载，且配置本身是廉价不可变对象，重复构造无成本。
    """
    return Settings()
