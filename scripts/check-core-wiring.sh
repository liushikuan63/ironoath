#!/usr/bin/env bash
# 职责：game-core 的每个主源码类都必须被 **core 之外的主源码**真正引用；没被引用的要么接上，
#       要么进白名单并写清理由 —— 守的是「内核写好了、外层从来没人装配」这一族（本仓头号缺陷形状）。
#
# 为什么开这道门（2026-10-06）：`验收矩阵.md:264` 的 B13 验收 10 长期挂着 ✅，理由是
#   `NationSystemTest` 36 条全绿。但现跑发现 `WarScoreBoard` 在 core 之外的主源码里**引用数为 0**
#   （只有它自己 + 一份单测），而它自己的类注释写着「由 game-web 在国战开始时载入、结束时落盘一次」
#   —— 契约写在注释里、生产里没人执行 ⇒ 玩家侧完全不可达。同一次扫描又抓出一个同形状孤儿
#   `InMemoryRewardPorts`（这个是故意的测试替身，见白名单）。
#   客户端那一侧早有 `check-client-send-paths.sh` 管"有名字零读者"，服务端这一侧一直没有。
#
# ⚠️ 三条"命中数必须 > 0"的下限，缺一条就判红（否则门自己失效时会假装干净）：
#   ① 扫到的 core 类数 > 0；② 扫到的外层主源码文件数 > 0；③ 白名单每条都必须**仍然命中**
#   —— 被豁免的类一旦哪天真接上了，条目就该删（腐烂的白名单比没有白名单更危险）。
#
# 口径：只认**非注释行**里的标识符。整文件 grep 会让"注释里提了一句"被当成已装配（假绿）。
#
# 可选输入口（**不设时逐字节等同改动前**，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   COREWIRING_CORE_DIR      内核目录，默认 `server/game-core/src/main/java`
#   COREWIRING_CONSUMER_DIR  外层根目录，默认 `server`（只取其中 `*/src/main/*` 且排除 target）
set -uo pipefail
cd "$(dirname "$0")/.."

CORE_DIR="${COREWIRING_CORE_DIR:-server/game-core/src/main/java}"
CONSUMER_DIR="${COREWIRING_CONSUMER_DIR:-server}"

# 白名单：类名 <TAB> 为什么可以没有生产引用（每条都要能被下一轮人判断"该不该撤"）
ALLOWED="$(mktemp)"; CLASSES="$(mktemp)"; WORDS="$(mktemp)"
trap 'rm -f "$ALLOWED" "$CLASSES" "$WORDS"' EXIT
cat > "$ALLOWED" <<'EOF'
InMemoryRewardPorts	故意的测试替身：四个发放端口的内存实现。放在 game-core 而不是 web 的 test 目录，是「RewardGrantor 可脱离容器单测」这条铁律的证明（见该类 javadoc）。生产实现是另一族 Spring Bean。撤销条件：无（它本来就不该有生产引用）。
WarScoreBoard	B13 国战整块未接线的已知缺口（不是"设计如此"）：该类 javadoc 写着「由 game-web 在国战开始时载入、结束时落盘一次」，但国战会话至今没有承载（`Nation` 聚合里没有积分板状态，也没有任何服务 new 它），所以硬加领取端点只会读一张永远为空的板 —— 那是第二处假绿。台账见 收口清单.md §七「2026-10-06 07:3x」与 验收矩阵.md:264 的就地更正。**撤销条件**：game-web 的 src/main 里出现 `WarScoreBoard` 的真实装配点（服务或 Bean 配置 new/restore 它）⇒ 立刻删掉本行。
EOF

if [ ! -d "$CORE_DIR" ]; then
  echo "[core-wiring][FAIL] 内核目录不存在：$CORE_DIR（fail-closed，不静默跳过）" >&2
  exit 1
fi
if [ ! -d "$CONSUMER_DIR" ]; then
  echo "[core-wiring][FAIL] 外层根目录不存在：$CONSUMER_DIR" >&2
  exit 1
fi

# ① core 的顶层类名（文件名即类名；内部 record/class 不算顶层）
find "$CORE_DIR" -name '*.java' | sed -E 's|.*/||; s|\.java$||' | sort -u > "$CLASSES"
CORE_N=$(grep -c . "$CLASSES" || true)
if [ "${CORE_N:-0}" -eq 0 ]; then
  echo "[core-wiring][FAIL] 在 $CORE_DIR 里一个类都没扫到 ⇒ 门在空转，判红而不是判绿" >&2
  exit 1
fi

# ② 外层主源码里出现过的标识符（剥掉注释行：`*`、`//`、`/*` 开头）
CONSUMER_FILES=$(find "$CONSUMER_DIR" -path '*/src/main/*' -name '*.java' -not -path '*/target/*' \
  -not -path "$CORE_DIR/*" 2>/dev/null)
CONSUMER_N=$(printf '%s' "$CONSUMER_FILES" | grep -c . || true)
if [ "${CONSUMER_N:-0}" -eq 0 ]; then
  echo "[core-wiring][FAIL] 外层主源码一个文件都没扫到（$CONSUMER_DIR）⇒ 判据失去对照面，判红" >&2
  exit 1
fi
printf '%s\n' "$CONSUMER_FILES" | tr -d '\r' | xargs -d '\n' cat 2>/dev/null \
  | grep -vE '^[[:space:]]*(\*|//|/\*)' \
  | grep -oE '[A-Z][A-Za-z0-9_]*' | sort -u > "$WORDS"

# 逐个类判定
missing=0
while IFS= read -r cls; do
  [ -n "$cls" ] || continue
  if grep -Fxq "$cls" "$WORDS"; then continue; fi
  if awk -F'\t' -v c="$cls" '$1==c{found=1} END{exit found?0:1}' "$ALLOWED"; then continue; fi
  file=$(find "$CORE_DIR" -name "$cls.java" | head -1)
  echo "[core-wiring][FAIL] $cls 在 core 之外的主源码里**零引用**（$file）" >&2
  echo "  ⇒ 要么把它装配进外层（服务 / 控制器 / Bean 配置），要么在 ALLOWED 里写明"为什么可以不接"并说清谁来撤这条豁免" >&2
  missing=$((missing + 1))
done < "$CLASSES"

# ③ 白名单腐烂检查：被豁免的类若已被真引用，条目就该删
stale=0
while IFS=$'\t' read -r cls reason; do
  [ -n "$cls" ] || continue
  if grep -Fxq "$cls" "$WORDS"; then
    echo "[core-wiring][FAIL] 白名单条目已失效：$cls 现在**有**生产引用了，该把这条豁免删掉" >&2
    stale=$((stale + 1))
  fi
  if [ -z "${reason:-}" ]; then
    echo "[core-wiring][FAIL] 白名单条目 $cls 没写理由 ⇒ 豁免必须带可判断的理由" >&2
    stale=$((stale + 1))
  fi
done < "$ALLOWED"

if [ "$missing" -gt 0 ] || [ "$stale" -gt 0 ]; then
  echo "[core-wiring] 不合格：未接线 $missing 个 · 白名单问题 $stale 条（core 类 $CORE_N 个，外层主源码 $CONSUMER_N 个文件）" >&2
  exit 1
fi

echo "[core-wiring] core 的 $CORE_N 个类全部有外层主源码引用（或带理由豁免 $(grep -c . "$ALLOWED" || true) 条）；对照面 = $CONSUMER_N 个外层文件"
