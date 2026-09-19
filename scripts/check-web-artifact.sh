#!/usr/bin/env bash
# 职责：检查 web-mobile 产物（量具与截图流水线吃的那一份）有没有把调试东西带给玩家。
# 依赖：node（读 settings.json 判构建档位）。
#
# 为什么单独一道门，而不是只写在 scripts/build-webmobile.sh 里：
# 构建脚本的那条判据**只有跑脚本时才生效**，而产物会被编辑器构建、被上一轮遗留、被别的会话覆盖
# —— 2026-09-19 查到的"release 包带着引擎 profiler 浮层（FPS / Draw call 一整块）"就是靠
# 目视截图才发现的，命令行构建默认 `showFPS=true`，谁都没碰过这个开关。
# 挂在 check.sh 上，才变成"每次静态检查都判一次"。
#
# 产物不存在时**如实 SKIPPED**，不判红也不假绿（与 check-wechat-artifact.sh 同一口径）。
set -euo pipefail
cd "$(dirname "$0")/.."

# 可覆盖是为了**能证伪**：植一个 showFPS=true / 缺 startScene 的假产物目录就能验这两条判据真的会红，
# 不必去动真实构建产物（并行会话正在吃它）。
BUILD_DIR="${WEB_ARTIFACT_DIR:-client/build/web-mobile}"

if [ ! -f "$BUILD_DIR/application.js" ] || [ ! -f "$BUILD_DIR/src/settings.json" ]; then
  echo "[check-web-artifact] SKIPPED：没有 $BUILD_DIR 产物（跑 bash scripts/build-webmobile.sh 生成）"
  exit 0
fi

DEBUG_BUILD=$(node -e '
  const fs = require("fs")
  const settings = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))
  process.stdout.write(settings.engine && settings.engine.debug ? "true" : "false")
' "$BUILD_DIR/src/settings.json")

if [ "$DEBUG_BUILD" = "false" ] && grep -q "this.showFPS = true" "$BUILD_DIR/application.js"; then
  echo "[check-web-artifact][FAIL] release 产物里 showFPS 是 true —— 玩家会看到引擎 profiler 浮层"
  echo "  用 bash scripts/build-webmobile.sh 重建（它按 debug 档位传 showFPS）。"
  exit 1
fi

# 曾经在这里加过第二条"startScene 必须烘进产物"，**已删**：uuid 在 web-mobile 产物里
# 一个字都不出现（`grep -rl bdd25b4c client/build/web-mobile` 零命中，微信侧才把它写进 settings），
# 那条判据会把**正常产物判红**。留在这里是为了说明为什么这个脚本只判一件事。
echo "[check-web-artifact] ${DEBUG_BUILD} 构建：产物里没有 profiler 浮层。"
