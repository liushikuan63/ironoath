#!/usr/bin/env bash
# 职责：可重复地构建 web-mobile 产物（量具与截图流水线吃的那一份）。
# 依赖：CocosCreator 3.8.7。
#
# 为什么要单独一个脚本，而不是把命令行抄在文档里：命令行构建的 **showFPS 默认是 true**，
# 于是 release 产物里带着引擎 profiler 浮层（FPS / Draw call 那一整块），玩家看到的就是这块调试面板
# —— 收口清单 #280 的地图截图抓到的。抄命令的人不会知道要加这一项，所以把参数收进脚本里。
#
# 为什么固定 startScene：命令行构建不读编辑器里的"默认场景"记忆，
# 不显式传 Boot.scene 的 UUID 时，换一台机器就可能构建出 startScene=undefined 的包。
set -euo pipefail
cd "$(dirname "$0")/.."

COCOS_CREATOR="${COCOS_CREATOR:-D:/Cocos/Creator/3.8.7/CocosCreator.exe}"
COCOS_DEBUG="${COCOS_DEBUG:-false}"
PROJECT_DIR="client"
BUILD_DIR="client/build/web-mobile"
BOOT_META="client/assets/scenes/Boot.scene.meta"

if [ ! -f "$COCOS_CREATOR" ]; then
  echo "[build-webmobile][FAIL] 找不到 CocosCreator：$COCOS_CREATOR"
  echo "  可用 COCOS_CREATOR 环境变量覆盖路径。"
  exit 1
fi
if [ ! -f "$BOOT_META" ]; then
  echo "[build-webmobile][FAIL] 找不到启动场景元数据：$BOOT_META"
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

echo "[build-webmobile] debug: $COCOS_DEBUG / showFPS: $COCOS_DEBUG / startScene: $BOOT_UUID"

BUILD_STARTED_AT=$(date +%s)

# Cocos 3.8.7 构建完成后常以 SIGTERM 收尾（退出码 36），产物其实已写好：
# 判绿只看两件事 —— 构建日志里 `missing or invalid` 的计数，与产物文件本身在不在。
set +e
"$COCOS_CREATOR" \
  --project "$PROJECT_DIR" \
  --build "platform=web-mobile;debug=$COCOS_DEBUG;showFPS=$COCOS_DEBUG;startScene=$BOOT_UUID" \
  --force > "${WEBMOBILE_LOG:-/tmp/webmobile-build.log}" 2>&1
COCOS_STATUS=$?
set -e
echo "[build-webmobile] CocosCreator 退出码=$COCOS_STATUS（36 = SIGTERM 收尾，判据在下面）"

MISSING=$(grep -c "missing or invalid" "${WEBMOBILE_LOG:-/tmp/webmobile-build.log}" || true)
if [ "$MISSING" != "0" ]; then
  echo "[build-webmobile][FAIL] 构建日志里有 $MISSING 行 missing or invalid —— 产物缺内容，不要拿去跑量具"
  exit 1
fi

for artifact in application.js index.html; do
  if [ ! -f "$BUILD_DIR/$artifact" ]; then
    echo "[build-webmobile][FAIL] 构建产物缺少 $BUILD_DIR/$artifact"
    exit 1
  fi
done

# 文件"在"不等于"这次构建写的"：上一轮的产物会一直躺在 build/ 里，
# 于是构建根本没跑（Cocos 路径写错、被系统杀掉、参数被拒）也能一路判绿到结尾。
# 所以要求启动场景那份产物比本次开始时间新。
ARTIFACT_MTIME=$(stat -c %Y "$BUILD_DIR/application.js")
if [ "$ARTIFACT_MTIME" -lt "$BUILD_STARTED_AT" ]; then
  echo "[build-webmobile][FAIL] $BUILD_DIR/application.js 是本次构建之前留下的（mtime $ARTIFACT_MTIME < $BUILD_STARTED_AT）"
  echo "  构建很可能根本没跑：检查 $COCOS_CREATOR 与 ${WEBMOBILE_LOG:-/tmp/webmobile-build.log} 的结尾。"
  exit 1
fi

if [ "$COCOS_DEBUG" = "false" ] && grep -q "this.showFPS = true" "$BUILD_DIR/application.js"; then
  echo "[build-webmobile][FAIL] release 产物里 showFPS 是 true —— 玩家会看到引擎 profiler 浮层"
  exit 1
fi

echo "[build-webmobile] 产物就绪：$BUILD_DIR（missing or invalid = 0，profiler 浮层已关）"
