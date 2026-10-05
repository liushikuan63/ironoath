#!/usr/bin/env bash
# 门禁：`tools/verify-*.mjs` 里**不要把「世界坐标」和「浏览器像素」直接比**。
#
# 为什么有这道门（2026-10-05 tieshi 验证轮，**同一类错误连踩两次**）：
#   `getWorldPosition()` 给的是 **Cocos 世界坐标**，而 `window.innerWidth/innerHeight` 是
#   **浏览器像素**。两者原点与比例都不同（实测本构建：世界 `[0,960]×[0,600]` 原点左下，
#   像素 `1440×900` 原点左上，差 `canvasSize/visibleSize = 1.5` 倍）。
#   ⇒ 直接写成 `Math.abs(v.x) > w / 2 || Math.abs(v.y) > h / 2` 这类式子**必错**，
#   而且**不一定报错**，只是让"量具读数整体偏掉" ⇒ 对照组先失准 ⇒ 探针退 2 或判成假红。
#   两次真实案例：`verify-panel-reachability`（像素 vs 世界坐标）、
#   `verify-plate-plant`（世界坐标 vs 截图像素）。
#
# 判据（本门只抓"**没有中转**"这一种，不做语义分析）：
#   同一份 `tools/verify-*.mjs` 里若**同时**出现
#     ① 取世界坐标：`getWorldPosition` / `getBoundingBoxToWorld`
#     ② 与 **`window.innerWidth` / `window.innerHeight`**（**浏览器像素**）比
#   **且**全文**没有** `worldToScreen` 这个中转，
#   ⇒ 判红，并提示走 `camera.worldToScreen()`。
#
# ⚠️⚠️ **判据第一版写错过，务必看懂再改**（2026-10-05 自测发现）：
#   我第一版把 `getVisibleSize()` / `getCanvasSize()` 也算进"视口像素"那一侧
#   ⇒ **5 份现有探针全部误报**，包括 `verify-guide-buttons-runtime.mjs`
#   ——**它自己的注释就写着**「世界坐标换算成屏幕像素再比（画布有缩放，
#   直接比世界坐标与 innerWidth 是错的口径）」，**它恰恰是对的**。
#   ⇒ ★ **`getVisibleSize()` / `getCanvasSize()` 是「设计分辨率」，与世界坐标**同空间**，
#   **拿它们与世界坐标比是合法的**；只有 **`window.innerWidth/innerHeight`
#   才是真正的浏览器像素**（实测本构建：世界 `[0,960]×[0,600]` vs 像素 `1440×900`，
#   差 1.5 倍且原点不同）。
# ⚠️ **有意保守**：只要文件里**有** `worldToScreen`，就放过 ——
#    它可能漏了另一处，但**宁可漏报也不误报**（误报会让无关的探针被改到能过为止）。
set -euo pipefail
cd "$(dirname "$0")/.."

# ⚠️ **自检开关（2026-10-05 补）**：`SELFTEST=1` 时先造一份违规探针、确认本门**真的会红**，
#    撤掉后再跑正式检查。⇒ 这样「本门是绿的」才有意义 ——
#    **0 命中是"没触发"，不等于"判据在工作"**（本会话在批跑上栽过同款：汇总全 0 ≠ 跑过了）。
if [ "${SELFTEST:-0}" = "1" ]; then
  probe="tools/verify-zz-selftest-coordinate.mjs"
  trap 'rm -f "$probe"' EXIT
  cat > "$probe" <<'EOF'
// 自检用违规样本（SELFTEST=1 时临时生成，跑完即删）
const h = window.innerHeight
const w = window.innerWidth
const v = new window.cc.Vec3()
node.getWorldPosition(v)
const hit = Math.abs(v.x) > w / 2 || Math.abs(v.y) > h / 2
EOF
  # ⚠️ 必须显式关掉子进程的 SELFTEST —— 否则它继承 SELFTEST=1 会**无限递归**（本会话实际卡死过一次）。
  if SELFTEST=0 bash "$0" >/dev/null 2>&1; then
    echo "[check-probe-coordinate-space][FAIL] 自检失败：植入违规样本后本门仍然绿 ⇒ 判据没在工作。" >&2
    exit 1
  fi
  rm -f "$probe"
  trap - EXIT
  echo "[check-probe-coordinate-space] 自检通过（植入违规 ⇒ 判红；撤掉 ⇒ 继续查真文件）。"
fi

bad=0
scanned=0
for f in tools/verify-*.mjs; do
  [ -f "$f" ] || continue
  scanned=$((scanned + 1))
  has_world=0
  has_viewport=0
  has_bridge=0
  grep -qE 'getWorldPosition|getBoundingBoxToWorld' "$f" && has_world=1
  grep -qE 'window\.(innerWidth|innerHeight)' "$f" && has_viewport=1
  grep -q 'worldToScreen' "$f" && has_bridge=1
  if [ "$has_world" = "1" ] && [ "$has_viewport" = "1" ] && [ "$has_bridge" = "0" ]; then
    bad=$((bad + 1))
    echo "[check-probe-coordinate-space] ✗ $f：同时取世界坐标（getWorldPosition/getBoundingBoxToWorld）" >&2
    echo "    又与 window.innerWidth/innerHeight（浏览器像素）比较，" >&2
    echo "    但全文没有 worldToScreen 中转 ⇒ 两边量纲不同，读数必偏。" >&2
    echo "    修法：camera.worldToScreen(v) 把世界坐标转成像素后再比；或把两边都换成世界单位。" >&2
  fi
done

# ⚠️ **下限断言（技能 assertion-discipline §三）**：一份探针都没扫到，是**故障**不是通过。
#    （`tools/verify-*.mjs` 被改名/挪走时，这道门会静默变成"永远绿"。）
if [ "$scanned" -lt 10 ]; then
  echo "[check-probe-coordinate-space][FAIL] 只扫到 $scanned 份探针（应至少 10）⇒ 扫描范围失效，不是通过。" >&2
  exit 1
fi

if [ "$bad" -gt 0 ]; then
  echo "[check-probe-coordinate-space] 失败：$bad 份探针可能把世界坐标和浏览器像素直接比。" >&2
  exit 1
fi
echo "[check-probe-coordinate-space] 通过：扫了 $scanned 份探针，没有一份在缺 worldToScreen 中转时混用世界坐标与视口像素。"