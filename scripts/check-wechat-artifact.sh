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

# DevTools 的 devtools 分支原写法会拿全局 window / global.document 去查属性描述符，
# worker 环境里它们可能不存在，首屏抛 "Cannot convert undefined or null to object"。
if grep -q "i.window||(i.window=r),i.document||(i.document={})" "$BUILD_DIR/web-adapter.js"; then
  echo "[check-wechat-artifact] DevTools 全局适配补丁：存在 ✓"
else
  echo "[check-wechat-artifact][FAIL] web-adapter.js 缺少 DevTools 全局适配补丁；"
  echo "  小游戏在开发者工具里首屏会抛 Cannot convert undefined or null to object。"
  echo "  处置：跑 node scripts/patch-wechat-adapter.mjs client/build/wechatgame/web-adapter.js"
  FAIL=1
fi

# 小游戏环境没有 Node 的 global；引擎里有直接引用它的分支，缺这行会在第一屏
# 抛 "ReferenceError: global is not defined"（开发者工具实测）。补丁必须排在所有代码之前。
if head -c 200 "$BUILD_DIR/game.js" | grep -q 'globalThis.global = globalThis.global || globalThis;'; then
  echo "[check-wechat-artifact] global 别名兼容补丁：存在 ✓"
else
  echo "[check-wechat-artifact][FAIL] game.js 缺少 global 别名兼容补丁；"
  echo "  小游戏首屏会抛 ReferenceError: global is not defined。"
  echo "  处置：跑 node scripts/patch-wechat-global.mjs client/build/wechatgame/game.js"
  echo "  （build-wechatgame.sh 已自动执行，这里只是防止有人手工重建后漏掉）。"
  FAIL=1
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
# 首包只量「玩家第一次下载就要拿到的那些文件」：subpackages/** 是 wx.loadSubPackage 按需拉的，
# 算进首包会变成「越分包越红」—— 与 check-package-size.sh 保持同一口径。
if [ -d "$BUILD_DIR/subpackages" ]; then
  SIZE=$(du -sb --exclude="$BUILD_DIR/subpackages" "$BUILD_DIR" | cut -f1)
  SUB_SIZE=$(du -sb "$BUILD_DIR/subpackages" | cut -f1)
else
  SIZE=$(du -sb "$BUILD_DIR" | cut -f1)
  SUB_SIZE=0
fi
TOTAL_SIZE=$((SIZE + SUB_SIZE))
mb() {
  node -e 'console.log((Number(process.argv[1]) / 1048576).toFixed(2))' "$1"
}
SIZE_MB=$(mb "$SIZE")
SUB_MB=$(mb "$SUB_SIZE")
TOTAL_MB=$(mb "$TOTAL_SIZE")
MAX_MB=$(mb "$FIRST_PACKAGE_MAX")
echo "[check-wechat-artifact] 本次是 ${DEBUG_BUILD} 构建；首包：${SIZE_MB}MB（预算 ${MAX_MB}MB，来源 global.PERF_FIRST_PACKAGE_MAX_BYTES）｜分包：${SUB_MB}MB｜整包：${TOTAL_MB}MB"
if [ "$SUB_SIZE" -gt 0 ]; then
  echo "[check-wechat-artifact] 分包目录存在，不计入首包判定；整包上限微信侧另有约束，本仓库暂无对应全局参数，只打印不判红。"
fi

if [ "$SIZE" -gt "$FIRST_PACKAGE_MAX" ]; then
  if [ "$DEBUG_BUILD" = "true" ]; then
    echo "[check-wechat-artifact][WARN] debug 包首包 ${SIZE_MB}MB 超预算 —— 只用于本地开发者工具，不判失败。"
    echo "  提审请用 release 构建；release 仍超限时再走分包/远程资源（B16 §1），不要删玩法功能。"
  else
    echo "[check-wechat-artifact][FAIL] release 首包 ${SIZE_MB}MB 超过 ${MAX_MB}MB。"
    echo "  先看构成：cocos-js/（引擎）与 assets/（图集）各占多少，再决定裁剪引擎模块还是把资源分包。"
    FAIL=1
  fi
fi

if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
echo "[check-wechat-artifact] 微信小游戏产物检查通过。"
