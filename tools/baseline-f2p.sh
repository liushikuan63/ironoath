#!/usr/bin/env bash
# 五维基线读数：一条命令跑完 balance-sim 的关键档位，把每档的关键行落盘并打印对照表。
#
# 为什么需要它（收口清单 #654）：`--f2p7d` 有 37 个开关，而 B02 的每一条结论都来自某一次
# 手工命令行。手工跑的问题不是「跑不动」，而是**跑完之后读数没有固定落点**：
# 改一处代码就要重跑十几条命令，且容易漏掉某几维的组合，最后留下的数字彼此不同源。
# ⇒ 本脚本把那十几条命令固化成一份基线：**同一个 commit 上跑两次，输出必须逐位相同**。
#
# 用法：bash tools/baseline-f2p.sh            # 默认 45 天
#       DAYS=120 bash tools/baseline-f2p.sh   # 换天数
#
# 退出码：0 全部档位跑完；1 有档位失败（构建/执行出错）。
#
# ⚠️ 本脚本**不做判定**，只收集读数 —— `BalanceCli` 自己的判定不通过时也退 1
# （输出末尾有「判定：…」），那是它的既有口径，不是本脚本的失败。
set -uo pipefail
cd "$(dirname "$0")/.."
DAYS="${DAYS:-45}"
OUT="tmp/baseline-f2p"
mkdir -p "$OUT"

# 档位表：每行 = 档名 + 额外参数。改这里就改了基线的覆盖面。
# ⚠️ 刻意包含**故意打开某一维**的档位：默认 builds 维被 --builds-gate=none 挡死（#652），
# 而 cap=false 与 priority=balanced 各是另一种模型，只跑默认档会以为「四维都跑过了」。
run() {
  local name="$1"; shift
  local args="--f2p7d --days=$DAYS $*"
  mvn -q -f server/pom.xml -pl tools/balance-sim exec:java -Dexec.args="$args" \
      > "$OUT/$name.txt" 2>&1
  local ec=$?
  if [ $ec -ne 0 ] && [ $ec -ne 1 ]; then
    echo "  !! $name 执行异常（exit=$ec）—— 先看 $OUT/$name.txt" >&2
    return 1
  fi
  # 抽关键行：dayN 终值 + 四类累计 + 产出建筑等级 + 造兵
  {
    grep -E "^ *${DAYS} " "$OUT/$name.txt" | tail -1
    grep -E "装备强化累计吃铁" "$OUT/$name.txt" | tail -1
    grep -E "建造累计吃粮" "$OUT/$name.txt" | tail -1
    grep -E "建造/仓库累计吃" "$OUT/$name.txt" | tail -1
    grep -E "累计溢出" "$OUT/$name.txt" | tail -1
    grep -E "产出建筑等级" "$OUT/$name.txt" | tail -1
    grep -E "累计造兵" "$OUT/$name.txt" | tail -1
  } | sed 's/^[[:space:]]*//' > "$OUT/$name.keys"
  printf '  %-14s ' "$name"
  head -1 "$OUT/$name.keys"
  return 0
}

echo "== balance-sim 五维基线（days=$DAYS）=="
echo "-- day$DAYS 终值：day 等级 木 石 铁 粮 当天升级数"
FAILED=0
run default            || FAILED=1
run tech8              -- --tech-level=8 || FAILED=1
run pop100             -- --population=100 || FAILED=1
run balanced           -- --priority=balanced || FAILED=1
run nocap              -- --cap=false || FAILED=1
run builds-all         -- --builds-gate=hospital,academy,stable,embassy,drill_ground || FAILED=1
run slots5             -- --train-slots=5 || FAILED=1
run no-troops          -- --dims=cap,builds,forge || FAILED=1

echo
echo "-- 各档关键行明细（存档：$OUT/<档名>.keys）--"
for f in "$OUT"/*.keys; do
  echo "## $(basename "$f" .keys)"
  sed 's/^/   /' "$f"
done

if [ "$FAILED" -ne 0 ]; then
  echo
  echo "有档位执行异常，见上方 !! 行" >&2
  exit 1
fi
echo
echo "基线跑完（$DAYS 天，8 档）。逐位对照：把 tmp/baseline-f2p 存下来，"
echo "下次改完代码再跑一次，diff 两份 .keys 就能看出哪些读数被影响。"
exit 0
