#!/usr/bin/env bash
# 职责：可重复地构建微信小游戏产物，并完成横屏与首包预算检查。
# 依赖：CocosCreator 3.8.7、node。
#
# 为什么固定 startScene：命令行构建不会读取编辑器里的“默认场景”记忆；
# 不显式传 Boot.scene 的 UUID 时，换一台机器就可能构建出 startScene=undefined 的包。
set -euo pipefail
cd "$(dirname "$0")/.."

COCOS_CREATOR="${COCOS_CREATOR:-D:/Cocos/Creator/3.8.7/CocosCreator.exe}"
COCOS_DEBUG="${COCOS_DEBUG:-false}"
# 开发期 AppID。Cocos 模板自带的 wx6ac3f5090a6b99c5 是"游客小游戏"，
# 新版 IDE 的自动化通道会直接拒绝它（日志：formatProject reject tourist/empty appid），
# 表现是"构建成功但打不开项目"。要换真实账号时用 WECHAT_GAME_APPID 覆盖。
WECHAT_GAME_APPID="${WECHAT_GAME_APPID:-wxa048c9e48c2fc7d1}"
PROJECT_DIR="client"
BUILD_DIR="client/build/wechatgame"
BOOT_META="client/assets/scenes/Boot.scene.meta"

if [ ! -f "$COCOS_CREATOR" ]; then
  echo "[build-wechatgame][FAIL] 找不到 CocosCreator：$COCOS_CREATOR"
  echo "  可用 COCOS_CREATOR 环境变量覆盖路径。"
  exit 1
fi

if [ ! -f "$BOOT_META" ]; then
  echo "[build-wechatgame][FAIL] 找不到启动场景元数据：$BOOT_META"
  exit 1
fi

BOOT_UUID=$(node -e '
  const fs = require("fs")
  const meta = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))
  if (typeof meta.uuid !== "string" || meta.uuid.length === 0) {
    console.error("Boot.scene.meta 缺少 uuid")
    process.exit(1)
  }
  process.stdout.write(meta.uuid)
' "$BOOT_META")

echo "[build-wechatgame] CocosCreator: $COCOS_CREATOR"
echo "[build-wechatgame] startScene: $BOOT_UUID"
echo "[build-wechatgame] debug: $COCOS_DEBUG"

set +e
"$COCOS_CREATOR" \
  --project "$PROJECT_DIR" \
  --build "platform=wechatgame;debug=$COCOS_DEBUG;startScene=$BOOT_UUID" \
  --force
COCOS_STATUS=$?
set -e

# Cocos 3.8.7 在 wechatgame 构建完成后偶发以 SIGTERM 收尾并返回非零码；
# 产物已经完整时继续做方向修补与检查，产物缺文件时仍然立刻失败。
if [ "$COCOS_STATUS" -ne 0 ]; then
  echo "[build-wechatgame][WARN] CocosCreator 退出码=$COCOS_STATUS；继续验证完整产物。"
fi

for artifact in game.json game.js application.js; do
  if [ ! -f "$BUILD_DIR/$artifact" ]; then
    echo "[build-wechatgame][FAIL] 构建产物缺少 $BUILD_DIR/$artifact"
    exit 1
  fi
done

node scripts/patch-wechat-orientation.mjs "$BUILD_DIR/game.json"
node scripts/patch-wechat-config.mjs "$BUILD_DIR/project.config.json" "$WECHAT_GAME_APPID"
node scripts/patch-wechat-adapter.mjs "$BUILD_DIR/web-adapter.js"
bash scripts/check-wechat-artifact.sh
echo "[build-wechatgame] 微信小游戏构建与产物检查通过。"
