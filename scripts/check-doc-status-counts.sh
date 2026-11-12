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
# ⚠️ 三个输入都可用环境变量换（验证这条检查真的会红，而不必改仓库里的文件）：
#    STATUSCOUNT_MATRIX / STATUSCOUNT_CHECKLIST / STATUSCOUNT_DEV。**不设时逐字节等同改动前**（模式隔离）。
#
# 第四组数是 2026-11-13 追加的「工程现数」两组：**探针份数**（`tools/verify-*.mjs` 的个数）与
# **GameApi 发送口个数**（`tools/report-client-send-paths.mjs` 打出来的 `GameApi 方法：N`）。
# 它们原先只写在 §二·补·一 那张表里、没人核，本轮现跑一次就发现两边都过期（表里 62 份 / 136 方法，
# 现数 87 份 / 155 方法）。这两行同样住在**被数的文档自己头部**。
#
# ⚠️ 声明行里**故意不靠 emoji 去切字段**：emoji 可能带/不带 U+FE0F 变体选择符，写进 sed 的
#    正则会在"某台机器上对、另一台上静默不匹配"（本门第一版就是这样：探针里最短的一条能匹配、
#    整行匹配不上）。所以声明行只按**出现顺序取整数**（矩阵 5 个、清单 2 个），并且先把
#    "命中恰好 1 处"与"整数个数对"当前置判据 —— 改坏声明行会红，不会被当成 0 放过。
set -uo pipefail
cd "$(dirname "$0")/.."

MATRIX="${STATUSCOUNT_MATRIX:-验收矩阵.md}"
CHECKLIST="${STATUSCOUNT_CHECKLIST:-上线检查清单.md}"
DEV="${STATUSCOUNT_DEV:-待完善收口_VibeCoding开发包.md}"

for f in "$MATRIX" "$CHECKLIST" "$DEV"; do
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

# ---------- 工程现数：探针份数 / GameApi 发送口 ----------
# 现跑口径与文档里那两条「现数」命令逐字一致（探针＝`ls tools/verify-*.mjs`，发送口＝报告器打的那行），
# 不在这里另立第二套定义。两个现跑口也都能换（自测用，见 STATUSCOUNT_PROBES_DIR / STATUSCOUNT_SEND_PATHS）。
PROBES_DIR="${STATUSCOUNT_PROBES_DIR:-.}"
A_PROBES=$(find "$PROBES_DIR/tools" -maxdepth 1 -name 'verify-*.mjs' 2>/dev/null | wc -l | tr -d ' ')
if [ "${A_PROBES:-0}" -eq 0 ]; then
  echo "[status-counts][FAIL] 在 $PROBES_DIR/tools 下一份 verify-*.mjs 都没数到 —— 谓词失效时判红而不是判绿" >&2
  exit 1
fi
if [ -n "${STATUSCOUNT_SEND_PATHS:-}" ]; then
  A_API=$(grep -oE 'GameApi 方法：[0-9]+' "$STATUSCOUNT_SEND_PATHS" | head -1 | grep -oE '[0-9]+')
else
  A_API=$(node tools/report-client-send-paths.mjs 2>/dev/null | grep -oE 'GameApi 方法：[0-9]+' | head -1 | grep -oE '[0-9]+')
fi
if [ -z "${A_API:-}" ]; then
  echo "[status-counts][FAIL] 取不到 GameApi 发送口个数（报告器没打出那行）—— 判红而不是当成 0" >&2
  exit 1
fi

D_ENG=$(node -e '
const fs = require("node:fs")
const hits = fs.readFileSync(process.argv[1], "utf8").split("\n").filter((l) => l.startsWith("> **现数"))
if (hits.length !== 1) {
  console.error("现数声明行命中处数不对：" + hits.length)
  process.exit(3)
}
const nums = hits[0].match(/[0-9]+/g) || []
if (nums.length !== 2) {
  console.error("现数声明行里的整数个数不对：" + JSON.stringify(nums))
  process.exit(4)
}
console.log(nums.join(" "))
' "$DEV") || {
  echo "[status-counts][FAIL] 取不到 $DEV 的工程现数声明（要恰好 1 行以 \"> **现数\" 开头、含 2 个整数）" >&2
  exit 1
}
read -r D_PROBES D_API <<<"$D_ENG"

FAILED=0
if [ "$D_PROBES" != "$A_PROBES" ] || [ "$D_API" != "$A_API" ]; then
  echo "[status-counts][FAIL] $DEV 的工程现数与现跑不一致：" >&2
  echo "  声明：探针 $D_PROBES 份 / GameApi $D_API 个发送口" >&2
  echo "  现跑：探针 $A_PROBES 份 / GameApi $A_API 个发送口" >&2
  echo "  ⇒ 加/删探针或改 GameApi 方法数必须同批改那行声明" >&2
  FAILED=1
fi
if [ "$FAILED" -ne 0 ]; then
  exit 1
fi

echo "[status-counts] ✓ $MATRIX 编号行 $A_NUM = ✅ $A_OK / 🟡 $A_MID / ⬜ $A_TODO / 其他 $A_OTHER；"\
"$CHECKLIST - [x] $A_DONE / - [ ] $A_TODOBOX；"\
"$DEV 探针 $A_PROBES 份 / GameApi $A_API 个发送口（声明值与现跑一致）"
exit 0
