#!/usr/bin/env bash
# 扫 --base-rate：量「产出要提高几倍，零氪玩家才能在 N 天到 30 级」。
#
# 为什么需要它（收口清单 #656）：B02 §3c 那个「需产出提高约 20 倍」的结论，
# 测于三个模型缺陷修复之前（#646 造价整除、#650 产出建筑只升第一座、#651 累计变量每天归零），
# 基准从 17 级变成 15 级之后倍数必然不同 ⇒ 必须按新基线重扫，否则那格一直悬着。
#
# ⚠️ `--base-rate` 的语义：`--base-rate=3` = **底产 × 3**（`BalanceCli` L494/L500，
# 乘到四个资源的底产上）；< 1 才是「缩到几成」，那是量仓容上限用的另一种用法。
#
# 用法：bash tools/sweep-base-rate.sh              # 扫 90 与 120 天
#       DAYS="90 120" bash tools/sweep-base-rate.sh
#       RATES="1 3 8 20" bash tools/sweep-base-rate.sh
#
# 退出码：0 全部跑完；1 有档位执行异常。
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh >/dev/null 2>&1
set +e   # env.sh 注入的 set -e 会让第一轮 grep 无匹配就把脚本带走（#645 记过）

DAYS="${DAYS:-90 120}"
RATES="${RATES:-1 3 8 20}"
OUT="tmp/sweep-base-rate"
mkdir -p "$OUT"

echo "== --base-rate 扫档（倍率 = 底产 × R）=="
printf '%-8s' "rate\\days"
for d in $DAYS; do printf '%-12s' "day$d"; done
echo
FAILED=0
for r in $RATES; do
  printf '%-8s' "x$r"
  for d in $DAYS; do
    f="$OUT/r${r}-d${d}.txt"
    mvn -q -f server/pom.xml -pl tools/balance-sim exec:java \
        -Dexec.args="--f2p7d --days=$d --base-rate=$r" > "$f" 2>&1
    ec=$?
    if [ $ec -ge 2 ]; then
      printf '%-12s' "ERR($ec)"
      FAILED=1
      continue
    fi
    # 取 dayN 那一行的等级（第 2 列），并把整行存档
    line=$(grep -E "^ *$d " "$f" | tail -1)
    if [ -z "$line" ]; then
      printf '%-12s' "NOLINE"
      FAILED=1
      continue
    fi
    echo "$line" | sed 's/^[[:space:]]*//' > "$OUT/r${r}-d${d}.keys"
    lvl=$(echo "$line" | awk '{print $2}')
    printf '%-12s' "Lv$lvl"
  done
  echo
done

echo
echo "-- 存档：$OUT/r<rate>-d<days>.keys（day 等级 木 石 铁 粮 当天升级数）--"
if [ "$FAILED" -ne 0 ]; then
  echo "有档位执行异常（ERR / NOLINE），先看 $OUT 下的 .txt" >&2
  exit 1
fi
echo "扫完。⚠️ 与 §3c 旧读数对比时注意：旧读数测于三个模型缺陷修复之前，基准不同。"
exit 0
