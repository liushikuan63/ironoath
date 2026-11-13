#!/usr/bin/env bash
# 道：`tools/` 下的每个脚本都得能被 node 解析。
#
# 为什么要有这一道：`tools/` 里现跑有 146 个 `.mjs`，**绝大多数只在人工跑探针时才被执行**
# （`check.sh` 只跑其中几份）。一次坏合并（少个括号、引号没闭合）会让整份探针静默失效 ——
# 症状是"这一份今天没结果"，而不是一条红；等到真去验某个面板时才发现那个工具早就坏了。
# `node --check` 只解析不执行，不碰网络与产物，因此可以进 CI。
#
# 判据（三层，谓词失效判红不判绿）：
#   1. 扫描目录存在；
#   2. 扫到的脚本数不低于下限（默认 120：目录写错会退化成 0 个文件，那时必须红而不是绿）；
#   3. 每一份都能 `node --check` 通过；失败的逐条打印。
#
# 走环境变量口，便于零污染自测：
#   TOOLSYNTAX_DIRS       扫描目录（空格分隔，默认 tools tools/lib scripts scripts/lib）
#   TOOLSYNTAX_MIN_FILES  脚本数下限（默认 120）
set -euo pipefail
cd "$(dirname "$0")/.."

DIRS="${TOOLSYNTAX_DIRS:-tools tools/lib scripts scripts/lib}"
MIN_FILES="${TOOLSYNTAX_MIN_FILES:-120}"

if ! command -v node >/dev/null 2>&1; then
  echo "[tool-syntax][FAIL] 找不到 node，无法解析脚本" >&2
  exit 1
fi

COUNT=0
BAD=0
BAD_LIST=""
for dir in $DIRS; do
  if [ ! -d "$dir" ]; then
    echo "[tool-syntax][FAIL] 扫描目录不存在：$dir（谓词失效）" >&2
    exit 1
  fi
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    COUNT=$((COUNT + 1))
    if ! node --check "$f" >/dev/null 2>&1; then
      BAD=$((BAD + 1))
      BAD_LIST="${BAD_LIST}  - $f"$'\n'
    fi
  done <<EOF
$(find "$dir" -maxdepth 1 -type f \( -name '*.mjs' -o -name '*.js' \) | sort)
EOF
done

if [ "$COUNT" -lt "$MIN_FILES" ]; then
  echo "[tool-syntax][FAIL] 只扫到 ${COUNT} 个脚本，低于下限 ${MIN_FILES}：谓词失效（目录是否写错？）" >&2
  exit 1
fi

if [ "$BAD" -ne 0 ]; then
  echo "[tool-syntax][FAIL] 有 ${BAD} 个脚本 node 解析不过（这些工具现在等于不存在）：" >&2
  printf '%s' "$BAD_LIST" >&2
  exit 1
fi

echo "[tool-syntax] ✓ ${COUNT} 个脚本全部通过 node --check（含只在人工跑探针时才会执行的那些）"
