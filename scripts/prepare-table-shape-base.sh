#!/usr/bin/env bash
# CI 只取本次 push 之前或 PR 基线那一个 commit；不拉全历史，不维护旧短行豁免清单。
set -euo pipefail

BASE="${TABLE_SHAPE_BASE_SHA:-}"
if [ -z "$BASE" ] || [[ "$BASE" =~ ^0+$ ]]; then
  BASE='HEAD^' # workflow_dispatch：checkout fetch-depth=2 至少有父提交
fi
if [ "$BASE" != 'HEAD^' ] && [[ ! "$BASE" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "[table-base][FAIL] 基线不是 commit SHA：$BASE" >&2
  exit 2
fi
if ! git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
  if [ "$BASE" = 'HEAD^' ]; then
    echo '[table-base][FAIL] HEAD^ 不可读：checkout 至少需要 fetch-depth=2。' >&2
    exit 2
  fi
  git fetch --no-tags --depth=1 origin "$BASE"
fi
if ! git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
  echo "[table-base][FAIL] 不能读取基线 $BASE，新增短行不得跳验。" >&2
  exit 2
fi
export TABLE_SHAPE_BASE_SHA="$BASE"
echo "[table-base] 与 $BASE 比较本次新增或改写的表格行。"
