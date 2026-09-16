#!/usr/bin/env bash
# 职责：CI 静态检查 —— 引导的**步骤文案只能住在服务端表里**（B18 验收 1、B12 禁止项）。
# 规则来源：
#   B12 §5 禁止项「引导逻辑硬编码进 UI 组件」；B18 验收 1「客户端全仓搜不到硬编码的步骤文案」
#   B18 禁止项「不要让引导步骤改一步就要发包」（热更是硬要求）
#
# 为什么用"逐条比对表里的原文"而不是"搜某个关键词"：关键词式检查会退化成一场捉迷藏 ——
# 有人把文案改一个字就绕过了，而真正的违规形状恰恰是"整句抄进组件"。
# 所以这里的判据是：guide.json 里每一行的 text，一个字符都不许出现在客户端源码里。
#
# 反空转：读到的文案条数为 0 就是检查坏了（表被移走 / 解析失败 / 字段改名），
# 直接失败而不是"通过"。曾经有过一次卡口因为切片规则写错而全绿空转，那条教训在这里防复发。
set -euo pipefail
cd "$(dirname "$0")/.."

report() { echo "[check-guide-copy] $*"; }
err() { echo "[check-guide-copy][FAIL] $*" >&2; }

SCAN_DIR='client/assets/scripts'
MIN_TEXTS=5

if [ ! -f contract/config/guide.json ]; then
  err "读不到 contract/config/guide.json —— 检查无从进行（宁可失败也不空转通过）"
  exit 1
fi

# 每行文案一条，行内换行折成空格（表里刻意不写换行，写坏了也照样能比对）
texts=$(node -e '
const rows = require("./contract/config/guide.json").rows || []
for (const r of rows) {
  if (typeof r.text === "string" && r.text.trim() !== "") {
    process.stdout.write(r.text.replace(/\s+/g, " ").trim() + "\n")
  }
}
')
count=$(printf '%s\n' "$texts" | grep -c . || true)

if [ "$count" -lt "$MIN_TEXTS" ]; then
  err "只读到 $count 条步骤文案（门槛 $MIN_TEXTS）—— 表或解析坏了，这个通过不算数"
  exit 1
fi

fail=0
for dir in "$SCAN_DIR" client/tests; do
  [ -d "$dir" ] || continue
  while IFS= read -r text; do
    [ -n "$text" ] || continue
    # fixed-string 匹配：文案里有标点与全角字符，按正则解释会误判成"没命中"
    hits=$(grep -rnF --include='*.ts' -- "$text" "$dir" || true)
    if [ -n "$hits" ]; then
      err "客户端源码里出现了表中的步骤文案：\"$text\""
      echo "$hits" >&2
      fail=1
    fi
  done <<< "$texts"
done

if [ "$fail" -ne 0 ]; then
  report "引导文案在客户端有了第二份家。表现是「服务端热更了脚本，玩家看到的还是旧提示」，"
  report "而这种情况所有测试都照绿 —— 因为测试读的是同一份硬编码。"
  exit 1
fi

report "$count 条步骤文案在 $SCAN_DIR 与 client/tests 里零命中：客户端只有一个通用驱动器，吃服务端下发的脚本。"
