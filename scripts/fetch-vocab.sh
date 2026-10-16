#!/usr/bin/env bash
# 拉取三本词书源文件到 data/vocab-source/
#
# 来源: https://github.com/KyleBing/english-vocabulary  (BSD 3-Clause License)
# 目录: full_line_jsonl/sentence/正序/
#
# 说明: 使用 sparse-checkout 只拉取需要的三个文件，避免克隆整个仓库
#      （仓库中含大量其它格式的词库，全量克隆在本机曾超时）。
#
# 用法: bash scripts/fetch-vocab.sh

set -euo pipefail

REPO_URL="https://github.com/KyleBing/english-vocabulary.git"
SRC_DIR="full_line_jsonl/sentence/正序"
SPARSE_PATH="$SRC_DIR"
DEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/data/vocab-source"
CLONE_DIR="$(mktemp -d)/english-vocabulary"

# 期望行数（来自 S3 / 需求文档的实测值，导入后必须一致）
declare -A EXPECTED=(
  ["CET4"]=7508
  ["CET6"]=5651
  ["KAOYAN"]=9602
)
declare -A SRC_FILE=(
  ["CET4"]="四级.jsonl"
  ["CET6"]="六级.jsonl"
  ["KAOYAN"]="考研.jsonl"
)

echo "==> 克隆（sparse）到 $CLONE_DIR"
git clone --depth 1 --filter=blob:none --sparse "$REPO_URL" "$CLONE_DIR"

cd "$CLONE_DIR"
echo "==> sparse-checkout set $SPARSE_PATH"
git sparse-checkout set --no-cone "$SPARSE_PATH"

mkdir -p "$DEST_DIR"

echo
echo "==> 复制并校验"
for key in CET4 CET6 KAOYAN; do
  src="$SPARSE_PATH/${SRC_FILE[$key]}"
  dst="$DEST_DIR/$key.jsonl"
  cp "$src" "$dst"
  actual=$(wc -l < "$dst" | tr -d ' ')
  expected=${EXPECTED[$key]}
  if [[ "$actual" == "$expected" ]]; then
    echo "  [OK]   $key.jsonl  $actual 行"
  else
    echo "  [FAIL] $key.jsonl  期望 $expected 行，实际 $actual 行" >&2
    exit 1
  fi
done

# 许可证（关于页需引用）
cp LICENSE "$DEST_DIR/LICENSE" 2>/dev/null || echo "  [WARN] 未找到 LICENSE 文件"

echo
echo "==> 完成。文件位置: $DEST_DIR"
