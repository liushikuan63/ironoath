#!/usr/bin/env bash
# 职责：一条命令跑完**内城运行期量具**（产物 + 一台本轮自己的后端 + 无头浏览器）。
# 依赖：已构建的 client/build/web-mobile 产物、server/game-web/target/game-web.jar、Playwright、Git Bash。
#
# 为什么需要它：这五件量具**进不了 `scripts/check.sh`** —— 那 26 道门是静态门（不需要后端、不需要浏览器，
# 在 CI 里跑得动），而这几件要"产物 + 活后端 + 无头浏览器"。于是复检一直是六条手工命令，
# 结果就是**没人复跑**（收口清单 #325 记的就是这件事）。这里把六条收成一条，并负责：
#   ① 在**自己的端口**起后端（绝不碰别人在跑的 8080/8157）；② 跑完把它关掉；③ 逐件报退出码。
#
# 用法：
#   node scripts/run-bash.mjs scripts/verify-runtime.sh
#   RUNTIME_PORT=8171 node scripts/run-bash.mjs scripts/verify-runtime.sh     # 换端口
# 退出码：0 全部通过；1 有量具判据失败；2 前置不满足（产物/ jar 缺失，或后端没起来）。
set -uo pipefail
cd "$(dirname "$0")/.."

PORT="${RUNTIME_PORT:-8163}"
OPS_TOKEN="${RUNTIME_OPS_TOKEN:-art-verify-local}"
JAR="server/game-web/target/game-web.jar"
ARTIFACT="client/build/web-mobile/index.html"
LOG="${RUNTIME_LOG:-tmp/verify-runtime-backend.log}"

if [ ! -f "$ARTIFACT" ]; then
  echo "[runtime][前置] 产物不存在：$ARTIFACT —— 先跑 node scripts/run-bash.mjs scripts/build-webmobile.sh"
  exit 2
fi
if [ ! -f "$JAR" ]; then
  echo "[runtime][前置] 后端 jar 不存在：$JAR —— 先跑 mvn -f server/pom.xml -pl game-web -am -DskipTests package"
  exit 2
fi
# **jar 必须比源码新**（2026-09-22 补：这条我自己踩过）。
# 症状：新加了一个端点、只 `mvn test/compile` 没 `package`，随后照旧起 jar 跑量具 ——
# 客户端点到那个端点拿到 404，界面上"点了没反应"，看起来像前端没接线（我为此查了三轮）。
# 复用旧产物会造出"编译绿而运行红"的假证据，所以这里直接失败，而不是打印一句 WARN。
NEWEST_SOURCE=$(find server -name '*.java' -newer "$JAR" -print -quit 2>/dev/null || true)
if [ -n "$NEWEST_SOURCE" ]; then
  echo "[runtime][前置] $JAR 比源码旧（例如 $NEWEST_SOURCE）—— 先重新 package，否则量的是旧产物"
  exit 2
fi
mkdir -p "$(dirname "$LOG")"

# 端口必须是**我们自己的**：先探一下，被占就明确失败，不去杀别人的进程。
if (echo > "/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo "[runtime][前置] 端口 $PORT 已被占用 —— 换一个：RUNTIME_PORT=8171 …（不动别人的进程）"
  exit 2
fi

# JDK 17：项目强制（本机默认 JAVA_HOME 可能是 8）
source scripts/env.sh

echo "[runtime] 起后端 :$PORT（日志 $LOG）"
java -jar "$JAR" --spring.profiles.active=dev --server.port="$PORT" \
  --ironoath.ops.token="$OPS_TOKEN" > "$LOG" 2>&1 &
BACKEND_PID=$!
# 关后端这件事必须写在 trap 里：中途任何一件量具失败都不许留一个孤儿进程占着端口。
cleanup() { kill "$BACKEND_PID" 2>/dev/null || true; wait "$BACKEND_PID" 2>/dev/null || true; }
trap cleanup EXIT

for _ in $(seq 1 60); do
  if grep -q "Started Application" "$LOG" 2>/dev/null; then break; fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "[runtime][前置] 后端进程提前退出，看 $LOG 尾部："
    tail -5 "$LOG"
    exit 2
  fi
  sleep 2
done
if ! grep -q "Started Application" "$LOG" 2>/dev/null; then
  echo "[runtime][前置] 120 秒内没等到启动完成，看 $LOG"
  exit 2
fi
echo "[runtime] 后端就绪（pid=$BACKEND_PID）"

export BACKEND_ORIGIN="http://localhost:$PORT"
TOOLS=(
  "tools/verify-art-runtime.mjs"
  "tools/verify-city-states.mjs"
  "tools/verify-city-build-many.mjs"
  "tools/verify-city-multi-types.mjs"
  "tools/verify-city-phone.mjs"
  # 军队训练队列的加速/取消：前置要养到主城 3 级 + 兵营 + 上阵武将（约 3 分钟），所以它排在最后
  "tools/verify-army-queue.mjs"
  "tools/verify-gift-popup.mjs"
)
FAILED=()
for tool in "${TOOLS[@]}"; do
  echo "[runtime] === $tool ==="
  if node "$tool"; then
    echo "[runtime] OK   $tool"
  else
    echo "[runtime] FAIL $tool（退出码 $?）"
    FAILED+=("$tool")
  fi
done

echo "[runtime] 汇总：$(( ${#TOOLS[@]} - ${#FAILED[@]} ))/${#TOOLS[@]} 通过"
if [ "${#FAILED[@]}" -gt 0 ]; then
  printf '[runtime] 失败项：%s\n' "${FAILED[*]}"
  exit 1
fi
echo "[runtime] 全部通过（后端随脚本退出一起关掉）"
