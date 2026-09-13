#!/usr/bin/env bash
# 职责：微信小游戏构建产物的发布前卡口 —— 横屏方向 + 首包体积预算。
# 依赖：node（读 game.json 与量体积）；产物由 CocosCreator 的 wechatgame 构建生成。
#
# 为什么必须是脚本而不是人记得检查：微信小游戏以竖屏启动时，游戏会被拉进一个
# 竖屏画布，而内城/地图按 960×640 横屏设计 —— 表现是首屏被压扁或只看到一角，
# 这类问题不会在 web-mobile 构建里出现，只有真机/开发者工具打开才会暴露。
# 首包预算是硬限制（超了不能发布），产物一旦被重新生成就可能悄悄超标。
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_DIR="client/build/wechatgame"
GAME_JSON="$BUILD_DIR/game.json"
GLOBAL_JSON="contract/config/global.json"

param() {
  node -e '
    const fs = require("fs")
    const rows = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).rows
    const hit = rows.find(r => r.id === process.argv[2])
    if (hit === undefined) {
      console.error("缺少全局参数 " + process.argv[2])
      process.exit(1)
    }
    console.log(hit.value)
  ' "$GLOBAL_JSON" "$1"
}

FAIL=0

if [ ! -f "$GAME_JSON" ]; then
  # 构建产物在 .gitignore 里：没构建过不是"检查失败"，而是"这次没有可检对象"。
  # 但一旦产物存在，下面每一条都是硬的（方向 / 预算）。
  echo "[check-wechat-artifact] 跳过：还没有 wechatgame 构建产物（先跑 CC开发全流程.md 阶段 5.2 的构建命令）。"
  exit 0
fi

ORIENTATION=$(node -e '
  const fs = require("fs")
  const game = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))
  process.stdout.write(String(game.deviceOrientation ?? ""))
' "$GAME_JSON")

if [ "$ORIENTATION" != "landscape" ]; then
  echo "[check-wechat-artifact][FAIL] game.json 的 deviceOrientation=${ORIENTATION:-<缺失>}，必须是 landscape。"
  echo "  内城与地图按 960×640 横屏设计；竖屏启动会被压扁/裁切，且不会在 web-mobile 构建里暴露。"
  echo "  处置：在 Cocos 构建面板把微信平台方向设为横屏，或构建后用 scripts/patch-wechat-orientation.mjs 归一化。"
  FAIL=1
else
  echo "[check-wechat-artifact] 方向：landscape ✓"
fi

# src/settings.json 的 engine.debug 就是本次构建模式：开发包要连开发者工具，
# 提审量的是 release 包 —— 两者体积差接近一倍（引擎开发版 5.57MB vs 发布版 2.60MB），
# 拿 debug 包判超限只会得到一个每次都要人工解释的假红。
DEBUG_BUILD=$(node -e '
  const fs = require("fs")
  const settings = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))
  process.stdout.write(settings.engine && settings.engine.debug ? "true" : "false")
' "$BUILD_DIR/src/settings.json")

FIRST_PACKAGE_MAX=$(param PERF_FIRST_PACKAGE_MAX_BYTES)
SIZE=$(du -sb "$BUILD_DIR" | cut -f1)
SIZE_MB=$(node -e 'console.log((Number(process.argv[1]) / 1048576).toFixed(2))' "$SIZE")
MAX_MB=$(node -e 'console.log((Number(process.argv[1]) / 1048576).toFixed(2))' "$FIRST_PACKAGE_MAX")
echo "[check-wechat-artifact] 本次是 ${DEBUG_BUILD} 构建；全量产物：${SIZE_MB}MB（预算 ${MAX_MB}MB，来源 global.PERF_FIRST_PACKAGE_MAX_BYTES）"

if [ "$SIZE" -gt "$FIRST_PACKAGE_MAX" ]; then
  if [ "$DEBUG_BUILD" = "true" ]; then
    echo "[check-wechat-artifact][WARN] debug 包 ${SIZE_MB}MB 超预算 —— 只用于本地开发者工具，不判失败。"
    echo "  提审请用 release 构建；release 仍超限时再走分包/远程资源（B16 §1），不要删玩法功能。"
  else
    echo "[check-wechat-artifact][FAIL] release 产物 ${SIZE_MB}MB 超过 ${MAX_MB}MB。"
    echo "  先看构成：cocos-js/（引擎）与 assets/（图集）各占多少，再决定裁剪引擎模块还是把资源分包。"
    FAIL=1
  fi
fi

if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
echo "[check-wechat-artifact] 微信小游戏产物检查通过。"
