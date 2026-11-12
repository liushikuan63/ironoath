#!/usr/bin/env bash
# 职责：台账里**声明的状态计数**必须等于现跑数出来的状态计数（V23-d 的第一次落地）。
#
# 为什么要有这道门（2026-11-13 现跑取证）：`待完善收口_VibeCoding开发包.md` §二·补·一 那张
# 「工程现数」表当时声明「验收矩阵 265 条 = 231 ✅ / 22 🟡 / 12 ⬜」「上线清单 [x] 70 / [ ] 38」
# 「静态门 45 道」「探针 62 份」，而当轮现跑是 **266 = 237 ✅ / 17 🟡 / 9 ⬜（另 3 行状态列写的是
# 「不适用/已裁决退役」，不是三种状态之一）**、**[x] 77 / [ ] 35**、**51 道**、**87 份**。
# 四组数字全错，而它们正是「这次验收到底还差多少」的唯一量化入口 —— 错的方向还一边倒
# （⬜ 声明 12、实际 9），照旧数字去排期会把已经做完的当没做，把没做的当做完。
#
# 判据形状照 `check-doc-counts.sh`：文档声明值 vs 现跑值，不一致即红；且**这两处声明分别住在
# 被数的文档自己头部**（验收矩阵与上线检查清单各一行 `> **现分布/现计数…`），不另立第三个家。
#
# ⚠️ 两个输入都可用环境变量换（验证这条检查真的会红，而不必改仓库里的文件）：
#    STATUSCOUNT_MATRIX / STATUSCOUNT_CHECKLIST。**不设时逐字节等同改动前**（模式隔离）。
#
# ⚠️ 声明行里**故意不靠 emoji 去切字段**：emoji 可能带/不带 U+FE0F 变体选择符，写进 sed 的
#    正则会在"某台机器上对、另一台上静默不匹配"（本门第一版就是这样：探针里最短的一条能匹配、
#    整行匹配不上）。所以声明行只按**出现顺序取整数**（矩阵 5 个、清单 2 个），并且先把
#    "命中恰好 1 处"与"整数个数对"当前置判据 —— 改坏声明行会红，不会被当成 0 放过。
set -uo pipefail
cd "$(dirname "$0")/.."

MATRIX="${STATUSCOUNT_MATRIX:-验收矩阵.md}"
CHECKLIST="${STATUSCOUNT_CHECKLIST:-上线检查清单.md}"

for f in "$MATRIX" "$CHECKLIST"; do
  if [ ! -f "$f" ]; then
    echo "[status-counts][FAIL] 输入文件不存在：$f（fail-closed，不静默跳过）" >&2
    exit 1
  fi
done

command -v node >/dev/null 2>&1 || {
  echo "[status-counts][FAIL] 找不到 node：计数要用 codePointAt 认 emoji，不能用 shell 的字节数近似" >&2
  exit 1
}

ACTUAL=$(node -e '
const { matrixStatusCounts, checklistCounts } = require("./scripts/lib/doc-status-counts.js")
const fs = require("node:fs")
const m = matrixStatusCounts(fs.readFileSync(process.argv[1], "utf8")).counts
const c = checklistCounts(fs.readFileSync(process.argv[2], "utf8"))
console.log([m.numbered, m["✅"], m["🟡"], m["⬜"], m.other, c.done, c.todo].join(" "))
' "$MATRIX" "$CHECKLIST") || {
  echo "[status-counts][FAIL] 计数脚本自身失败（不是台账问题）" >&2
  exit 1
}
read -r A_NUM A_OK A_MID A_TODO A_OTHER A_DONE A_TODOBOX <<<"$ACTUAL"

if [ "${A_NUM:-0}" -eq 0 ]; then
  echo "[status-counts][FAIL] 在 $MATRIX 里一条编号验收项都没数到 —— 谓词失效时判红而不是判绿" >&2
  exit 1
fi
if [ $((A_OK + A_MID + A_TODO + A_OTHER)) -ne "$A_NUM" ]; then
  echo "[status-counts][FAIL] 分类计数 $((A_OK + A_MID + A_TODO + A_OTHER)) ≠ 编号行数 $A_NUM（计数逻辑坏了）" >&2
  exit 1
fi

DECLARED=$(node -e '
const fs = require("node:fs")
const lines = fs.readFileSync(process.argv[1], "utf8").split("\n")
const boxed = fs.readFileSync(process.argv[2], "utf8").split("\n")
const matrixHits = lines.filter((l) => l.startsWith("> **现分布"))
const checklistHits = boxed.filter((l) => l.startsWith("> **现计数"))
if (matrixHits.length !== 1 || checklistHits.length !== 1) {
  console.error("命中处数不对：" + JSON.stringify({ matrix: matrixHits.length, checklist: checklistHits.length }))
  process.exit(3)
}
const matrixNumbers = matrixHits[0].match(/[0-9]+/g) || []
const checklistNumbers = checklistHits[0].match(/[0-9]+/g) || []
if (matrixNumbers.length !== 5 || checklistNumbers.length !== 2) {
  console.error("声明行里的整数个数不对：" + JSON.stringify({ matrixNumbers, checklistNumbers }))
  process.exit(4)
}
console.log([...matrixNumbers, ...checklistNumbers].join(" "))
' "$MATRIX" "$CHECKLIST") || {
  echo "[status-counts][FAIL] 取不到两份文档的声明值（声明行被删/改形；每份必须恰好 1 处，矩阵 5 个整数、清单 2 个整数）" >&2
  exit 1
}
read -r D_NUM D_OK D_MID D_TODO D_OTHER D_DONE D_TODOBOX <<<"$DECLARED"

if [ $((D_OK + D_MID + D_TODO + D_OTHER)) -ne "$D_NUM" ]; then
  echo "[status-counts][FAIL] $MATRIX 的声明自身不自洽：$D_OK+$D_MID+$D_TODO+$D_OTHER ≠ 编号行 $D_NUM" >&2
  exit 1
fi

FAILED=0
if [ "$D_NUM" != "$A_NUM" ] || [ "$D_OK" != "$A_OK" ] || [ "$D_MID" != "$A_MID" ] ||
   [ "$D_TODO" != "$A_TODO" ] || [ "$D_OTHER" != "$A_OTHER" ]; then
  echo "[status-counts][FAIL] $MATRIX 声明的分布与现跑不一致：" >&2
  echo "  声明：编号行 $D_NUM = ✅ $D_OK / 🟡 $D_MID / ⬜ $D_TODO / 其他 $D_OTHER" >&2
  echo "  现跑：编号行 $A_NUM = ✅ $A_OK / 🟡 $A_MID / ⬜ $A_TODO / 其他 $A_OTHER" >&2
  echo "  ⇒ 翻完状态必须同批改那行声明（就地改写数字；不要只改状态列）" >&2
  FAILED=1
fi
if [ "$D_DONE" != "$A_DONE" ] || [ "$D_TODOBOX" != "$A_TODOBOX" ]; then
  echo "[status-counts][FAIL] $CHECKLIST 声明的计数与现跑不一致：" >&2
  echo "  声明：- [x] $D_DONE / - [ ] $D_TODOBOX" >&2
  echo "  现跑：- [x] $A_DONE / - [ ] $A_TODOBOX" >&2
  FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  exit 1
fi

echo "[status-counts] ✓ $MATRIX 编号行 $A_NUM = ✅ $A_OK / 🟡 $A_MID / ⬜ $A_TODO / 其他 $A_OTHER；"\
"$CHECKLIST - [x] $A_DONE / - [ ] $A_TODOBOX（声明值与现跑一致）"
exit 0
