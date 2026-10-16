#!/usr/bin/env bash
# 根目录便捷入口：转发到 backend/scripts/smoke-test.sh
# 用法与全部环境变量说明见 backend/scripts/smoke-test.sh 头部注释。
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/backend/scripts/smoke-test.sh" "$@"
