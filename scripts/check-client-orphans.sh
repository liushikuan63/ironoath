#!/usr/bin/env bash
# 职责：客户端 game/ 与 scene/ 下的每个 .ts 都必须被**另一个生产文件导入**（或被场景/预制体挂载）。
#       没人导入 = 写完了但玩家够不着 —— 与 check-core-wiring 守的是同一族，只是换到客户端这一侧。
#
# 为什么开这道门（2026-10-06）：`验收矩阵.md:94`（B04 验收 8「多个奖励按顺序播放、不堆叠遮挡」）长期挂 ✅，
#   证据是 `RewardToastQueue.test.ts` 全绿。现跑：`RewardToastQueue` 的 import 只出现在
#   `client/tests/` 里，`client/assets/scripts` 生产侧**零导入**，参数 `TOAST_MAX_QUEUE` 也只活在
#   该类注释与 global.json ⇒ 飘字顺序与丢弃上限玩家永远看不到。
#   客户端这一侧此前只有 `check-client-send-paths`（管 GameApi 方法有没有人调），
#   **域类**这一维没人守 —— 全仓 80 个 game/scene 文件现跑只有 1 个孤儿，正是它，所以这道门可以**零白名单**开。
#
# ⚠️ 三条"命中数必须 > 0"下限：扫到的 .ts 数、判定为已接线数、白名单条目仍然有效（腐烂的豁免比没有更危险）。
# ⚠️ Cocos 组件可以只由 .scene/.prefab 按 uuid 挂载而从不被 import ⇒ 导入检查失败时**再查一次 uuid**，
#   查到就算已接线。这个兜底只会把"误判的违规"放回绿，不会把"真孤儿"放成绿（单向放宽，方向是安全的）。
#
# 可选输入口（**不设时逐字节等同改动前**，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   ORPHAN_SCAN_DIRS   空格分隔的扫描根，默认 `client/assets/scripts/game client/assets/scripts/scene`
#   ORPHAN_ASSET_DIR   场景/预制体所在根，默认 `client/assets`
set -uo pipefail
cd "$(dirname "$0")/.."

SCAN_DIRS="${ORPHAN_SCAN_DIRS:-client/assets/scripts/game client/assets/scripts/scene}"
ASSET_DIR="${ORPHAN_ASSET_DIR:-client/assets}"

# 白名单：文件名 <TAB> 理由 <TAB> 撤销条件
ALLOWED="$(mktemp)"; FILES="$(mktemp)"; IMPORTED="$(mktemp)"
trap 'rm -f "$ALLOWED" "$FILES" "$IMPORTED"' EXIT
cat > "$ALLOWED" <<'EOF'
MainCity	B01 阶段的占位主城场景，**它自己的文件注释就写着**「删掉这个文件，游戏逻辑不受任何影响 —— 这是判断表现层有没有越界的尺子」。现跑：Boot.scene 里没有它的压缩 uuid（meta 头 8b2dd467 在场景里 0 命中），其余提及全在别的视图文件注释里。⇒ 是"留作参照的死码"，不是没接的功能。**撤销条件**：要么删掉本文件（连带删 .ts.meta 并复跑 check-ts-meta），要么把它接回某个场景 ⇒ 删掉本行。
EOF

for d in $SCAN_DIRS; do
  if [ ! -d "$d" ]; then
    echo "[client-orphans][FAIL] 扫描根不存在：$d（fail-closed，不静默跳过）" >&2
    exit 1
  fi
done

# ① 待判文件（排除生成物：生成的类型/配置由 npm run gen 产出，本来就不该有人手工导入）
find $SCAN_DIRS -name '*.ts' -not -path '*/generated/*' 2>/dev/null | sort > "$FILES"
TOTAL=$(grep -c . "$FILES" || true)
if [ "${TOTAL:-0}" -eq 0 ]; then
  echo "[client-orphans][FAIL] 在 [$SCAN_DIRS] 里一个 .ts 都没扫到 ⇒ 门在空转，判红而不是判绿" >&2
  exit 1
fi

# ② 生产侧出现过的 import 目标（取 basename）：一次扫完，别对 80 个文件各跑一遍 grep
grep -rhoE "from '[^']+'" $SCAN_DIRS 2>/dev/null \
  | sed -E "s|.*[\\/]||; s|'$||" | sort -u > "$IMPORTED"
if [ "$(grep -c . "$IMPORTED" || true)" -eq 0 ]; then
  echo "[client-orphans][FAIL] 一条 import 都没抽到 ⇒ 谓词失效（客户端源码形状变了？），判红" >&2
  exit 1
fi

uuid_referenced() {
  # 只有 import 查不到时才走这条路：组件可能被 .scene/.prefab 按 uuid 挂载
  # ⚠️ Cocos 在场景里存的是**压缩 uuid**（保留前 5 位十六进制 + 其余做 base64），
  #    拿完整 uuid 去 grep 永远搜不到 ⇒ 本轮实测 `GameBootstrap` 被误判成孤儿
  #    （场景里是 `de341Wd9G9BbKflbHnZok+b`，meta 是 `de34159d-f46f-...`）。
  local meta="$1.meta"
  [ -f "$meta" ] || return 1
  local u head
  u=$(node -e '
    const fs = require("fs");
    try { console.log(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).uuid || ""); }
    catch (e) { console.log(""); }
  ' "$meta" 2>/dev/null)
  [ -n "${u:-}" ] || return 1
  head="${u:0:5}"
  [ "${#head}" -ge 5 ] || return 1
  grep -rl --include=*.scene --include=*.prefab -F "$head" "$ASSET_DIR" >/dev/null 2>&1
}

orphans=0
wired=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  name=$(basename "$f" .ts)
  if grep -Fxq "$name" "$IMPORTED"; then wired=$((wired + 1)); continue; fi
  if awk -F'\t' -v c="$name" '$1==c{found=1} END{exit found?0:1}' "$ALLOWED"; then continue; fi
  if uuid_referenced "$f" "$name"; then wired=$((wired + 1)); continue; fi
  echo "[client-orphans][FAIL] $name 没有任何生产导入（$f）⇒ 写完了但玩家够不着" >&2
  echo "  ⇒ 要么接到面板 / AppRoot（并同批给 GameApi 发送口，见 check-client-send-paths），" >&2
  echo "    要么删掉它；留在树里就会一直被「有单测所以已验收」误读" >&2
  orphans=$((orphans + 1))
done < "$FILES"

if [ "$wired" -eq 0 ]; then
  echo "[client-orphans][FAIL] 判定为「已接线」的文件数 = 0（共 $TOTAL 个）⇒ 门只会判红或什么都量不到，判红" >&2
  exit 1
fi
if [ "$orphans" -gt 0 ]; then
  echo "[client-orphans] 不合格：$orphans 个零导入文件（扫描 $TOTAL 个，已接线 $wired 个）" >&2
  exit 1
fi

echo "[client-orphans] $TOTAL 个 game/scene 文件全部已接线（已接线 $wired 个，豁免 $(grep -c . "$ALLOWED" || true) 条）"
