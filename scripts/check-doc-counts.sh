#!/usr/bin/env bash
# 职责：AGENTS.md 里声明的「静态门（N 道）」必须等于 scripts/check.sh 实际调用的道数。
#
# 为什么专门开一道门管这个数字（2026-10-06）：本仓库的跨会话接续全靠 AGENTS.md 那张
# 「验证入口」表 —— 它写「32 道」而脚本里实际 40 道是本轮现跑抓到的（同一轮还抓到
# check.sh 自述客户端单测「941 项」、现跑 1029 项）。计数一漂，后面每一次会话都会拿旧数
# 当「门全开」的证据，而这正是 AGENTS.md §四 第 1 条要防的形状。
# ⚠️ 靠人记得改数字是改不住的：把 32 改成 41 之后，下一个加门的人同样不会记得再改一次
#    ⇒ 只能让门自己说，且**这道门自己也在被数之内**（加它就要同批改文档，否则它红）。
#
# ⚠️ 两个输入都可用环境变量换（验证这条检查真的会红，而不必改仓库里的文件）：
#    DOCCOUNT_CHECK_SH / DOCCOUNT_AGENTS_MD。**不设时逐字节等同改动前**（模式隔离）。
set -uo pipefail
cd "$(dirname "$0")/.."

CHECK_SH="${DOCCOUNT_CHECK_SH:-scripts/check.sh}"
AGENTS_MD="${DOCCOUNT_AGENTS_MD:-AGENTS.md}"

for f in "$CHECK_SH" "$AGENTS_MD"; do
  if [ ! -f "$f" ]; then
    echo "[doc-counts][FAIL] 输入文件不存在：$f（fail-closed，不静默跳过）" >&2
    exit 1
  fi
done

# 实际道数：行首就是 `bash scripts/check-*.sh` 的调用（非注释）。
# ⚠️ 谓词只认「行首 + bash scripts/check-」：注释里的示例、`node scripts/…`、
#    以及 check-gates-can-fail.sh（明令不接进 check.sh）都不算一道门。
ACTUAL=$(grep -cE "^[[:space:]]*bash scripts/check-[a-z0-9_-]+\.sh" "$CHECK_SH" || true)
ACTUAL=${ACTUAL:-0}
if [ "$ACTUAL" -eq 0 ]; then
  echo "[doc-counts][FAIL] 在 $CHECK_SH 里一道门都没数到 —— 谓词失效时判红而不是判绿" >&2
  exit 1
fi

# 文档声明：`静态门（N 道）`（全角括号，与 AGENTS.md 的写法一致）
HITS=$(grep -cE "静态门（[0-9]+ 道）" "$AGENTS_MD" || true)
HITS=${HITS:-0}
if [ "$HITS" -ne 1 ]; then
  echo "[doc-counts][FAIL] 「静态门（N 道）」在 $AGENTS_MD 里命中 $HITS 处，应当只有 1 处真源" >&2
  echo "  ⇒ 0 处＝表格被改坏；≥2 处＝两个数字会打架" >&2
  exit 1
fi
DECL=$(grep -oE "静态门（[0-9]+ 道）" "$AGENTS_MD" | head -1 | grep -oE "[0-9]+" || true)
if [ -z "${DECL:-}" ]; then
  echo "[doc-counts][FAIL] 命中 1 处但取不到数字（$AGENTS_MD）" >&2
  exit 1
fi

if [ "$DECL" != "$ACTUAL" ]; then
  echo "[doc-counts][FAIL] $AGENTS_MD 声明 $DECL 道，$CHECK_SH 实际调用 $ACTUAL 道" >&2
  echo "  ⇒ 加了/删了门禁就要同批改文档；把 $AGENTS_MD 的数字改成 $ACTUAL" >&2
  exit 1
fi

echo "[doc-counts] 静态门道数一致：$DECL 道（现数 $ACTUAL，输入 $CHECK_SH）"
