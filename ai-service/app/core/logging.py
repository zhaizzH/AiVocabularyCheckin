"""日志配置（spec 指定格式，只配一次）。

格式：``%(asctime)s %(levelname)s %(name)s %(message)s``

红线：**绝不可**记录 API key、请求体（含用户学习数据）、prompt 全文。
单词文本可以记录（它来自词库、非用户隐私），但 ``wrongContext``（含用户学习行为）不记。
"""

from __future__ import annotations

import logging

LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"

_configured = False


def configure_logging(level: str = "INFO") -> None:
    """在进程内配置一次根 logger。

    重复调用是幂等的：测试会反复创建 app，不能让 handler 不断累积
    （否则同一条日志会被打印 N 次）。
    """
    global _configured
    if _configured:
        logging.getLogger().setLevel(level.upper())
        return

    logging.basicConfig(level=level.upper(), format=LOG_FORMAT, force=True)
    _configured = True


def get_logger(name: str) -> logging.Logger:
    """取得带统一前缀的 logger。"""
    return logging.getLogger(name)
