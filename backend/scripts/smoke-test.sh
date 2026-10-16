#!/usr/bin/env bash
# =====================================================================
# 一键自测：跑通后端全部对外接口断言（含 AI 降级路径）。
#
# 用法：
#   DBPASS=<mysql密码> bash backend/scripts/smoke-test.sh
#
# 可选环境变量：
#   DBPASS / DB_PASSWORD   MySQL 密码（二选一，必须提供）
#   DB_HOST / DB_PORT / DB_NAME / DB_USERNAME   默认 127.0.0.1 / 3306 / ai_vocab / root
#   BASE                   后端地址，默认 http://127.0.0.1:8080
#   SKIP_UNIT              设为 1 则跳过 mvn test（只跑 HTTP 冒烟）
#   KEEP_SERVICE           设为 1 则脚本结束后不关闭它启动的后端进程
#
# 行为：
#   1) 若 BASE 上已有服务则复用；否则用 target/*.jar（不存在先打包）以 local profile 启动；
#   2) 依次跑 5 个冒烟脚本（它们只创建/清理自己的前缀测试数据）；
#   3) 除 SKIP_UNIT=1 外，再跑 `mvn -o test`；
#   4) 打印汇总，有任何 FAIL 则以非零退出码结束。
#
# 安全：脚本绝不触碰既有数据与红线库 anime_tracker；只创建并清理自己前缀
#       （g4_/g4cd_/g4n_/g5_/g5t_/stage9*/g6adm_/g6usr_ 用户与 G6冒烟 词书）的数据。
# 前置：AI 降级是一等命题，要求 127.0.0.1:8000 **不可达**（脚本会检测并拒绝启动）。
# =====================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND="$ROOT/backend"
BASE="${BASE:-http://127.0.0.1:8080}"
PORT="${BASE##*:}"
PORT="${PORT%%/*}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-ai_vocab}"
DB_USERNAME="${DB_USERNAME:-root}"
DBPASS="${DBPASS:-${DB_PASSWORD:-}}"

JAR="$BACKEND/target/vocab-backend-1.0.0.jar"
LOG="$ROOT/.tmp/smoke-test-service.log"
STARTED_PID=""

TOTAL_FAIL=0
SUMMARY=()

info()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
note()  { printf '   %s\n' "$*"; }
fail()  { printf '\033[1;31m!! %s\033[0m\n' "$*"; }

cleanup() {
  if [ -n "$STARTED_PID" ] && [ "${KEEP_SERVICE:-0}" != "1" ]; then
    info "关闭本脚本启动的后端进程 (pid=$STARTED_PID)"
    kill "$STARTED_PID" 2>/dev/null || true
    wait "$STARTED_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ---------------------------------------------------------------- 前置检查
if [ -z "$DBPASS" ]; then
  fail "缺少 MySQL 密码：请设置 DBPASS 或 DB_PASSWORD 环境变量"
  echo "   例：DBPASS=yourpass bash backend/scripts/smoke-test.sh"
  exit 2
fi

if ! command -v python >/dev/null 2>&1; then
  fail "缺少 python（冒烟脚本依赖标准库 urllib/json）"
  exit 2
fi

if ! command -v java >/dev/null 2>&1; then
  fail "缺少 java"
  exit 2
fi

# ------------------------------------------------- 服务是否已在运行 / 拉起
# 401 也算「活着」——受保护接口本来就该返回 401。
service_alive() {
  python - "$1" <<'PY' 2>/dev/null || true
import sys, urllib.request, urllib.error
try:
    urllib.request.urlopen(sys.argv[1] + "/api/client/auth/profile", timeout=3)
    print(200)
except urllib.error.HTTPError as e:
    print(e.code)
except Exception:
    pass
PY
}

if [ -n "$(service_alive "$BASE")" ]; then
  info "检测到 $BASE 已有服务在运行，直接复用"
else
  info "启动后端（$BASE）"
  mkdir -p "$ROOT/.tmp"
  if [ ! -f "$JAR" ]; then
    note "未找到 jar，先执行 mvn -o -q clean package -DskipTests"
    (cd "$BACKEND" && mvn -o -q clean package -DskipTests) || { fail "打包失败"; exit 3; }
  fi
  note "jar: $JAR"
  note "日志: $LOG"
  ( cd "$BACKEND" && nohup java -jar "$JAR" \
      --spring.profiles.active=local \
      --server.port="$PORT" \
      --spring.datasource.password="$DBPASS" \
      > "$LOG" 2>&1 & echo $! > "$ROOT/.tmp/smoke-test.pid" )
  STARTED_PID="$(cat "$ROOT/.tmp/smoke-test.pid")"

  note "等待端口 $PORT 就绪（最多 90s）..."
  ready=0
  for _ in $(seq 1 90); do
    if [ -n "$(service_alive "$BASE")" ]; then ready=1; break; fi
    sleep 1
  done
  if [ "$ready" != "1" ]; then
    fail "服务在 90s 内未就绪；末尾 30 行日志："
    tail -30 "$LOG" || true
    exit 3
  fi
  note "服务已就绪 (pid=$STARTED_PID)"
fi

# AI 降级是一等命题：g5 要求 ai-service **不可达**。
# 下段 python 退出码：0 = 端口开着（脚本应拒绝启动）；1 = 端口关闭（符合预期）。
if python - <<'PY'
import socket, sys
s = socket.socket()
s.settimeout(1)
try:
    s.connect(("127.0.0.1", 8000))
except OSError:
    sys.exit(1)
finally:
    s.close()
sys.exit(0)
PY
then
  fail "检测到 127.0.0.1:8000 有服务在监听——g5 降级断言要求 ai-service 不可达，请先停掉它"
  exit 2
fi

# --------------------------------------------------------------- 冒烟脚本

# 统计脚本输出：PASS 行数 / FAIL 行数 / 脚本自带汇总行。
# 用 python 解析而不是 grep —— 冒烟脚本输出是中文 UTF-8，在 GBK 终端下
# grep 会误判（实测把 46 PASS 数成 47）。
count_smoke() {        # $1=输出文件
  python - "$1" <<'PY'
import io, re, sys
raw = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
raw = re.sub(r"\x1b\[[0-9;]*m", "", raw)           # 去掉 ANSI 颜色
lines = raw.splitlines()
p = sum(1 for l in lines if l.strip().startswith("PASS") and not l.strip().startswith("PASS:"))
f = sum(1 for l in lines if l.strip().startswith("FAIL") and not l.strip().startswith("FAIL:"))
summary = ""
for l in lines:
    if "汇总" in l:
        summary = l.strip()
print("%d\t%d\t%s" % (p, f, summary))
PY
}

run_smoke() {          # $1=脚本名  $2=展示名
  local script="$BACKEND/scripts/$1"
  local out="$ROOT/.tmp/smoke-$1.out"
  info "$2  (scripts/$1)"
  if [ ! -f "$script" ]; then fail "脚本不存在：$script"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  if DBPASS="$DBPASS" DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_NAME="$DB_NAME" \
     DB_USERNAME="$DB_USERNAME" python "$script" "$BASE" > "$out" 2>&1; then
    stats="$(count_smoke "$out")"
    p="$(printf '%s' "$stats" | cut -f1)"
    f="$(printf '%s' "$stats" | cut -f2)"
    summ="$(printf '%s' "$stats" | cut -f3)"
    note "exit=0  ${summ:-${p} PASS / ${f} FAIL}"
    SUMMARY+=("$2: ${p:-?} PASS / ${f:-?} FAIL")
    if [ "${f:-0}" != "0" ]; then
      TOTAL_FAIL=$((TOTAL_FAIL+1))
      printf '   失败的用例：\n'
      grep -aE '^[[:space:]]*FAIL' "$out" | sed 's/^/     /' || true
    fi
  else
    fail "exit!=0，末尾 15 行："
    tail -15 "$out" | sed 's/^/     /'
    SUMMARY+=("$2: 执行失败")
    TOTAL_FAIL=$((TOTAL_FAIL+1))
  fi
}

run_smoke g4-smoke.py              "G4 核心闭环（学习/打卡/复习/错题，契约 §3-§5）"
run_smoke g4-crossday.py           "G4 跨日规则（streak 递增/重置、跨天复习）"
run_smoke g5-smoke.py              "G5 AI 中转与降级（契约 §6，一等命题：ai-service 未启动）"
run_smoke stage9-stats-smoke.py    "阶段 9 统计（契约 §7 热力图/掌握度）"
run_smoke stage10-admin-smoke.py   "阶段 10 管理模块（契约 §8，10 接口）"

# ---------------------------------------------------------------- 单元测试
if [ "${SKIP_UNIT:-0}" = "1" ]; then
  info "跳过 mvn test（SKIP_UNIT=1）"
else
  info "全量单元/集成测试（H2 内存库，不依赖本机 MySQL）"
  unit_log="$ROOT/.tmp/smoke-mvn-test.log"
  if (cd "$BACKEND" && mvn -o test) > "$unit_log" 2>&1; then
    last="$(python - "$unit_log" <<'PY'
import io, re, sys
raw = re.sub(r"\x1b\[[0-9;]*m", "", io.open(sys.argv[1], encoding="utf-8", errors="replace").read())
rows = [l.strip() for l in raw.splitlines() if re.search(r"Tests run:.*Failures", l)]
print(rows[-1] if rows else "SUCCESS")
PY
)"
    note "BUILD SUCCESS  ${last}"
    SUMMARY+=("mvn -o test: ${last:-SUCCESS}")
  else
    fail "mvn -o test 失败，末尾 30 行："
    tail -30 "$unit_log" | sed 's/^/     /'
    SUMMARY+=("mvn -o test: FAILED")
    TOTAL_FAIL=$((TOTAL_FAIL+1))
  fi
fi

# ------------------------------------------------------------------- 汇总
info "自测汇总"
for line in "${SUMMARY[@]}"; do note "$line"; done

if [ -n "$STARTED_PID" ]; then
  errs="$(python - "$LOG" <<'PY'
import io, re, sys
raw = re.sub(r"\x1b\[[0-9;]*m", "", io.open(sys.argv[1], encoding="utf-8", errors="replace").read())
print(sum(1 for l in raw.splitlines() if " ERROR " in l))
PY
)"
  note "服务日志 ERROR 行数：${errs:-0}   ($LOG)"
  [ "${errs:-0}" = "0" ] || TOTAL_FAIL=$((TOTAL_FAIL+1))
fi

if [ "$TOTAL_FAIL" = "0" ]; then
  printf '\n\033[1;32m全部通过 ✅\033[0m\n'
  exit 0
else
  printf '\n\033[1;31m有 %s 项未通过 ❌\033[0m\n' "$TOTAL_FAIL"
  exit 1
fi
