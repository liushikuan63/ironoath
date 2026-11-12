#!/usr/bin/env bash
# 职责：**生产配置里不许出现明文凭据** —— `application-prod.yml` 的每个敏感项只能是 `${ENV}` 占位。
#
# 为什么要有这道门（2026-11-13 现跑取证）：`上线检查清单.md` §七-1「`application-prod.yml` 的敏感值
# 全部来自环境变量」这条长期是 `[ ]`，而现读那 53 行里 **11 处敏感项确实都是 `${IRONOATH_…}` 占位、
# 没有明文**。也就是说：**这件事此刻是对的，但没有任何东西拦着它以后变错**。
# 生产凭据一旦被顺手粘进 yml（`app-key-prod: wx1234…`），它会跟着 git 历史永久留在仓库里，
# 而所有现存门都看不见 —— 契约同步、分层、发送口、payload 都不读这份文件。
#
# 判据（三条，缺一条就可能假绿）：
#   ① 敏感键（名字里含 password/secret/token/app-key/credential/private-key/access-key 或以 uri 结尾）
#      的取值必须是空，或恰好是一个 `${ENV}` / `${ENV:默认}` 占位；出现别的形态即红。
#   ② 占位数量有下限（默认 6）：文件被改瘦/改形时判红，而不是"一条敏感键都没扫到"就退 0。
#   ③ 文件存在性 + 非空：输入缺失时判红（fail-closed），不静默跳过。
#
# 不做什么：不判断"哪个环境变量必须设"（那是部署手册的事），也不读环境变量值 ——
# 这道门只回答一个问题：**这份文件里有没有明文凭据**。
#
# 零污染自测口：`PRODCFG_FILE=<夹具>` 可换输入；不设时逐字节等同默认行为。
set -uo pipefail
cd "$(dirname "$0")/.."

FILE="${PRODCFG_FILE:-server/game-web/src/main/resources/application-prod.yml}"
MIN_PLACEHOLDERS="${PRODCFG_MIN_PLACEHOLDERS:-6}"

if [ ! -f "$FILE" ]; then
  echo "[prod-config-secrets][FAIL] 配置文件不存在：$FILE（fail-closed，不静默跳过）" >&2
  exit 1
fi

if ! grep -qE '[^[:space:]]' "$FILE"; then
  echo "[prod-config-secrets][FAIL] 配置文件是空的：$FILE" >&2
  exit 1
fi

# ① 敏感键的取值形态
VIOLATIONS=$(awk '
function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }
{
  line = $0
  if (line ~ /^[[:space:]]*#/) next          # 注释行不算
  at = index(line, ":")
  if (at == 0) next                          # 不是 key: value（比如列表项）
  key = trim(substr(line, 1, at - 1))
  if (key == "") next
  rest = substr(line, at + 1)
  # 去掉行尾注释（只在空白 + # 之后才算注释，避免把 # 当成值的一部分）
  sub(/[[:space:]]+#.*$/, "", rest)
  val = trim(rest)
  low = tolower(key)
  sensitive = (low ~ /password|secret|token|app-key|credential|private-key|access-key/ || low ~ /(^|\.)uri$/)
  if (!sensitive) next
  if (val == "") next
  if (val ~ /^\$\{[A-Za-z0-9_]+(:[^}]*)?\}$/) next
  printf "%d: %s = %s\n", NR, key, val
}' "$FILE")

if [ -n "$VIOLATIONS" ]; then
  echo "[prod-config-secrets][FAIL] 生产配置里出现非占位的敏感值（生产凭据不得进仓库，粘一次就跟进 git 历史）：" >&2
  printf '%s\n' "$VIOLATIONS" | sed 's/^/  - /' >&2
  echo "  ⇒ 改成 \${IRONOATH_XXX} 形式，并在部署手册/环境变量清单里登记这个名字" >&2
  exit 1
fi

# ② 占位数量下限：文件被改瘦/改形（比如整段被删）时判红，而不是"没扫到敏感键"就退 0
COUNT=$(grep -oE '\$\{[A-Za-z0-9_]+' "$FILE" | wc -l | tr -d ' ')
if [ "$COUNT" -lt "$MIN_PLACEHOLDERS" ]; then
  echo "[prod-config-secrets][FAIL] 只扫到 $COUNT 处环境变量占位（下限 $MIN_PLACEHOLDERS）——" \
       "这份文件的样子变了，本门可能已扫不到东西（判红而不是假绿）" >&2
  exit 1
fi

echo "[prod-config-secrets] ✓ $FILE：敏感项全部为环境变量占位（共 $COUNT 处 \${…}），无明文凭据"
exit 0
