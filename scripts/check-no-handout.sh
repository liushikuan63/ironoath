#!/usr/bin/env bash
# 职责：CI 静态检查 —— 禁止任何「施舍机制」（B08 验收 10、B08/B00 头号禁止项）。
# 规则来源：
#   B08 禁止项「不要实现向下收益衰减（打弱者收益打折）」
#   B08 禁止项「不要实现弱者自动攻防补偿（基于战力差给 buff）」
#   B08 §6「绝对不要实现：基于战力差的自动补偿（旧设计的 min((R-1)×0.4, 0.30) 已作废）」
#   C01 错误二「给弱者自动补偿 → 战力失去意义」
#   C01 错误三「降低虐菜收益 → 杀死社交的起点」
#
# 为什么需要静态检查而不只是评审：这两种机制的失败模式不是崩溃，而是「游戏变得没人玩」——
# 加进去之后所有测试照常通过，只在几个月后表现为强者流失或弱者退游。
# 到那时已经没人记得是哪次改动引入的，而回退意味着收回玩家已经拿到的收益。
# 所以在它进代码库的那一刻就拦住，比事后排查便宜得多。
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
report() { echo "[check-no-handout] $*"; }
err() { echo "[check-no-handout][FAIL] $*" >&2; fail=1; }

# 过滤掉注释行：grep -rn 输出为 文件:行号:内容，只锚定「:行号:」之后的内容起始，
# 避免 Windows 绝对路径里的盘符冒号干扰匹配。
# 说明文档里提到这些词是在解释「为什么禁止」，不是实现，必须跳过。
COMMENT_LINE_FILTER=':[0-9]+:[[:space:]]*(\*|//|/\*|#)'

# 禁止的标识符与公式形状。刻意收窄到「只可能出现在真实实现里」的模式：
#   · 命名类：weak/underdog/loser + bonus/buff/compensation/handout 的组合
#   · 收益衰减类：loot/reward/yield + decay/reduce/penalty 且与 power/ratio 同现
#   · 旧设计残留：min((R-1)×0.4, 0.30) 这一类「按战力差算补偿」的公式形状
#   · 中文命名：向下收益衰减 / 弱者补偿 / 弱者加成 / 战力差补偿
FORBIDDEN_REGEX='([Ww]eak|[Uu]nderdog|[Ll]oser|[Ii]nferior)(ness)?_?(Bonus|Buff|Compensation|Handout|Assist|Relief)'
FORBIDDEN_REGEX="${FORBIDDEN_REGEX}|(bonus|buff|compensation|handout|assist|relief)_?([Ff]or)?_?([Ww]eak|[Uu]nderdog|[Ll]oser)"
FORBIDDEN_REGEX="${FORBIDDEN_REGEX}|(loot|reward|yield|drop)(Decay|Reduction|Penalty|Shrink)(By|Per|On)?(Power|Ratio|Gap)"
FORBIDDEN_REGEX="${FORBIDDEN_REGEX}|(power|ratio|gap)(Based)?(Compensation|Handout|Subsidy)"
FORBIDDEN_REGEX="${FORBIDDEN_REGEX}|compensate(For)?(Power|Ratio|Gap|Weaker)"
FORBIDDEN_REGEX="${FORBIDDEN_REGEX}|向下收益衰减|收益衰减|弱者补偿|弱者加成|弱者保送|战力差补偿|压分补偿"
FORBIDDEN_REGEX="${FORBIDDEN_REGEX}|\(R[[:space:]]*-[[:space:]]*1\)[[:space:]]*\*[[:space:]]*0\.4"

SCAN_TARGETS=(
  server/game-common/src/main
  server/game-config/src/main
  server/game-core/src/main
  server/game-battle/src/main
  server/game-web/src/main
  client/assets/scripts
)

for dir in "${SCAN_TARGETS[@]}"; do
  [ -d "$dir" ] || continue
  for pattern in '*.java' '*.ts'; do
    hits=$(grep -rnE "$FORBIDDEN_REGEX" "$dir" --include="$pattern" \
      | grep -vE "$COMMENT_LINE_FILTER" || true)
    if [ -n "$hits" ]; then
      err "$dir 中存在疑似「施舍机制」的代码（B08 头号禁止项 / C01 错误二三）："
      echo "$hits" >&2
    fi
  done
done

# 正向断言：圈层校验必须只有一个实现入口，且真的被接上了。
# B08 禁止项「不要新增绕过统一中间件校验的代码路径」——
# B08 §2 要求「发起攻击 / 搜索目标 / 发起集结」三个入口统一校验，
# 其中集结属 B10、攻击的战斗结算属 B09，但校验本身现在就该在两个入口上生效。
# 因此这里要求「至少两个不同文件」调用 PowerBandGuard.check：
# 只有一个文件说明另一个入口在自己算区间（或者根本没算）。
guard_hits=$(grep -rn "PowerBandGuard.check" server --include='*.java' \
  | grep -v '/test/' | grep -vE "$COMMENT_LINE_FILTER" || true)
guard_uses=$(printf '%s' "$guard_hits" | grep -c "PowerBandGuard.check" || true)
guard_files=$(printf '%s\n' "$guard_hits" | sed -E 's/^([^:]+):[0-9]+:.*/\1/' | sort -u | grep -c . || true)
if [ "$guard_uses" -lt 1 ]; then
  err "PowerBandGuard.check 在主源码里没有任何调用点：圈层校验尚未接线（B08 §2 要求三个入口统一校验）"
elif [ "$guard_files" -lt 2 ]; then
  err "PowerBandGuard.check 只在 $guard_files 个文件里被调用：搜索目标与发起攻击是两个独立入口，"
  err "只有一个入口走统一校验，另一个必然在自己算区间（B08 禁止项）"
else
  report "圈层校验入口（$guard_files 个文件）："
  printf '%s\n' "$guard_hits" | sed -E 's/^([^:]+):([0-9]+):.*/  \1:\2/' >&2
fi

raw_ratio_uses=$(grep -rnE "fixedParam\(\"PVP_POWER_(MIN|MAX)_RATIO\"\)|longParam\(\"PVP_POWER_(MIN|MAX)_RATIO\"\)" \
  server --include='*.java' | grep -v '/test/' | grep -vE "$COMMENT_LINE_FILTER" || true)
# 只允许圈层规则的装配处读取这两个参数；别处读取意味着有人在自己算区间
allowed_ratio_reads=$(echo "$raw_ratio_uses" | grep -c "PowerService\|PowerBandRules\|PowerRulesFactory\|MatchRuleService" || true)
total_ratio_reads=$(echo "$raw_ratio_uses" | grep -c "PVP_POWER" || true)
if [ "$total_ratio_reads" -gt 0 ] && [ "$allowed_ratio_reads" -eq 0 ]; then
  err "PVP_POWER_*_RATIO 有 $total_ratio_reads 处读取，但没有一处在圈层规则的装配处："
  echo "$raw_ratio_uses" >&2
  err "这意味着有人在绕过 PowerBandGuard 自己算区间（B08 禁止项）"
elif [ "$total_ratio_reads" -ne "$allowed_ratio_reads" ]; then
  report "提示：PVP_POWER_*_RATIO 共 $total_ratio_reads 处读取，其中 $allowed_ratio_reads 处在装配处，请复核其余各处"
  echo "$raw_ratio_uses" >&2
fi

# B08 禁止项：√N 必须用 FixedPoint.sqrt，禁止 Math.sqrt(double)。
# 只对「圈层路径上的文件」生效 —— FogOfWar 用 Math.sqrt 算 chunk 网格边长是合法的，
# 那是一个显示用的整数边长，不参与任何判定；而圈层的边界值判定差 1 个定点单位
# 就会让「战力恰好等于上限」的攻击在一台机器上被允许、在另一台上被拒绝。
sqrt_offenders=""
# 单文件 grep 的输出是「行号:内容」，注释过滤的正则相应地不带前面那个冒号
SQRT_COMMENT_FILTER='^[0-9]+:[[:space:]]*(\*|//|/\*|#)'
while IFS= read -r file; do
  [ -n "$file" ] || continue
  # 跳过注释行：PowerBandGuard 的 Javadoc 里就写着「禁止 Math.sqrt(double)」，
  # 那是规则说明而不是实现，不过滤就会自己检举自己
  if grep -n "Math\.sqrt" "$file" | grep -vE "$SQRT_COMMENT_FILTER" | grep -q .; then
    sqrt_offenders="${sqrt_offenders}${file}"$'\n'
  fi
done < <(grep -rlE "PowerBandGuard|rallySize" server client --include='*.java' --include='*.ts' 2>/dev/null \
         | grep -v '/test/' || true)
if [ -n "$sqrt_offenders" ]; then
  err "圈层路径上的文件使用了 Math.sqrt(double)，必须换成 FixedPoint.sqrt（B08 §2 原文禁止项）："
  printf '%s' "$sqrt_offenders" >&2
fi

if [ "$fail" -ne 0 ]; then
  report "施舍机制检查未通过。"
  report "弱者的出路是组团反击（集结 √N 破圈 + 一键求援 + 复仇/哀兵/围剿加成），不是系统保送。"
  exit 1
fi
report "施舍机制检查通过：无向下收益衰减、无基于战力差的自动补偿，圈层校验有 $guard_uses 处调用。"
