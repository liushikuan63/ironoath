#!/usr/bin/env bash
# 职责：禁止**新增**"按子节点下标取节点"的写法（`node.children[3]` / `children.slice(3, 5)`）。
#
# 为什么需要它：视图里"长什么样"与"代码按下标取"绑在一起，是这一族缺陷的温床 ——
# `ArmyPanelView` 的 `children.slice(3, 5)` 曾在行池化复用时把读数指到了**别的行**上，
# 导致"按钮明明出来了"被判成没出来（2026-09-22 军队队列探针实际踩过）。
# 2026-09-22 全量清点时存量 **43 处 / 10 个视图文件**（见下面的 BASELINE），
# 所以这道门不要求一次清完，只要求**只减不增**：新增即红，减少必须同步改基线。
#
# 判据（都能失败）：
#   ① 实际处数 > 基线 ⇒ 红（有人新写了按下标取节点）；
#   ② 实际处数 < 基线 **也**红 —— 数字变小了却不更新基线，说明这份账已经不准，
#      下一个人会以为"还剩 43 处"，而真正的债早就不是这个数了；
#   ③ 自检：`VIEW_DIR` 指向一个含违规的临时目录时必须判红（证明这条判据真的会响）。
#
# 用法：
#   bash scripts/check-view-child-index.sh              # 正常跑（门禁用法）
#   VIEW_DIR=/tmp/xxx bash scripts/check-view-child-index.sh   # 自检用：换扫描目录
#   PRINT=1 bash scripts/check-view-child-index.sh      # 只打印当前各文件处数（改基线时用）
# 退出码：0 合规；1 判据失败；2 前置不满足（扫描目录不存在）。
set -uo pipefail
cd "$(dirname "$0")/.."

VIEW_DIR="${VIEW_DIR:-client/assets/scripts/scene}"

# 存量基线（2026-09-22 清点）。**只减不增**；减少时把新数字填回来，别让这份账失效。
BASELINE_TOTAL=43
# 单文件基线：新增文件必须从 0 开始（不出现在这张表里就只允许 0 处）
declare -A BASELINE=(
  [ArmyPanelView.ts]=4
  [BagPanelView.ts]=4
  [BattlePlaybackView.ts]=1
  [BattleReportPanelView.ts]=3
  [GachaDisclosureView.ts]=2
  [MailPanelView.ts]=4
  [QuestPanelView.ts]=8
  [SocialPanelView.ts]=9
  [StagePanelView.ts]=4
  [TargetSearchView.ts]=4
)

if [ ! -d "$VIEW_DIR" ]; then
  echo "[view-child-index][前置] 扫描目录不存在：$VIEW_DIR"
  exit 2
fi

count_of() {  # $1 = 文件路径
  grep -cE 'children\[[0-9]+\]|children\.slice\([0-9]' "$1" 2>/dev/null || true
}

if [ "${PRINT:-0}" = "1" ]; then
  total=0
  for file in "$VIEW_DIR"/*.ts; do
    [ -e "$file" ] || continue
    n=$(count_of "$file")
    [ "$n" = "0" ] && continue
    echo "  $(basename "$file") $n"
    total=$((total + n))
  done
  echo "合计 $total"
  exit 0
fi

failures=()
total=0
for file in "$VIEW_DIR"/*.ts; do
  [ -e "$file" ] || continue
  name=$(basename "$file")
  n=$(count_of "$file")
  [ -z "$n" ] && n=0
  total=$((total + n))
  allowed=${BASELINE[$name]:-0}
  if [ "$n" -gt "$allowed" ]; then
    # 逐行点名，方便直接改：按下标取节点的行都列出来
    detail=$(grep -nE 'children\[[0-9]+\]|children\.slice\([0-9]' "$file" | head -3 | tr '\n' ' ')
    failures+=("$name 按下标取节点 $n 处 > 基线 $allowed 处（$detail）")
  fi
done

if [ "$total" -gt "$BASELINE_TOTAL" ]; then
  echo "[view-child-index][FAIL] 总处数 $total > 基线 $BASELINE_TOTAL —— 新增了按下标取节点"
  printf '  %s\n' "${failures[@]}"
  echo "  改法：给节点起名字（addLabel 的 name、按钮的 name 都在），用 getChildByName 取。"
  exit 1
fi
if [ "$total" -lt "$BASELINE_TOTAL" ]; then
  echo "[view-child-index][FAIL] 总处数 $total < 基线 $BASELINE_TOTAL —— 债还掉了，但基线没跟着改"
  echo "  把本脚本顶部的 BASELINE_TOTAL 与 BASELINE 改成新数字（PRINT=1 可打印当前分布）。"
  exit 1
fi

echo "[view-child-index] 按下标取节点：$total 处（= 基线，只减不增）；10 个视图文件的存量见脚本顶部"
