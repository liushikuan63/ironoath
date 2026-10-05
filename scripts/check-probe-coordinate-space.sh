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

bad=0
for f in tools/verify-*.mjs; do
  [ -f "$f" ] || continue
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

if [ "$bad" -gt 0 ]; then
  echo "[check-probe-coordinate-space] 失败：$bad 份探针可能把世界坐标和浏览器像素直接比。" >&2
  exit 1
fi
echo "[check-probe-coordinate-space] 通过：没有探针在缺 worldToScreen 中转时混用世界坐标与视口像素。"