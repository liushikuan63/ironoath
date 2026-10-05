#!/usr/bin/env bash
# 批跑官方**双后端**入口 —— 2026-10-05 新增。
#
# 为什么需要它（台账 17:8x / 17:9x）：
#   `verify-nation-live` 与 `verify-nation-policy-ui` 要的是**开着 dev 提速档**的后端
#   （`IRONOATH_DEV_CITY_LEVEL=16` + `IRONOATH_DEV_START_AMOUNT=2000000`，只在 dev profile 生效）。
#   ⚠️ **这两个变量是后端进程读的，设在探针侧完全没用** —— 必须给这两份**另一台后端**。
#   `run-runtime-probes.sh` 因此新增 `BOOST_BACKEND` / `BOOST_PROBES` 来分流。
#
# 用法：
#   bash scripts/run-batch-dual-backend.sh [文件清单]
#   可覆盖：BOOST_PORT（默认 8198）· BACKEND_PORT（默认 8199）
#           RUNTIME_PROBES_TIMEOUT（默认 900）· RUNTIME_PROBES_LOGDIR（默认 /d/tmp）
#           LOG（默认 /d/tmp/probe753/batch-dual.log）
#
# ⚠️ **默认不传 `BOOST_BACKEND` 时 `run-runtime-probes.sh` 行为与改动前完全一致**
#   ⇒ 本脚本是**新增入口**，不是改现有入口；只想要单后端时照旧跑 `run-runtime-probes.sh`。
#
# ★ 起后端 + 跑批跑 + 收后端必须在**同一个脱离进程树**里：
#   后端随启动它的 shell 一起死（踩过）。
# ★ 判服务就绪一律**探活**（`curl`），不要 `grep` 日志里的启动横幅：
#   日志是追加缓冲，那行可能在刷出前就被判超时 ⇒ 脚本会卡住不写汇总（踩过）。
set -u
cd "$(dirname "$0")/.."
export PATH="/d/Java/nodejs/node20.13.0:$PATH"

BACKEND_PORT="${BACKEND_PORT:-8199}"
BOOST_PORT="${BOOST_PORT:-8198}"
TIMEOUT="${RUNTIME_PROBES_TIMEOUT:-900}"
LOG="${LOG:-/d/tmp/probe753/batch-dual.log}"
LOGDIR="${RUNTIME_PROBES_LOGDIR:-/d/tmp}"
JDK="/d/Java/jdk/microsoft-jdk-17/bin/java.exe"
JAR="server/game-web/target/game-web.jar"

mkdir -p "$(dirname "$LOG")"
: > "$LOG"

# 探活到返回就 0，最多 70 秒
wait_up() {
  for _ in $(seq 1 70); do
    if curl -s -o /dev/null -m 2 "http://127.0.0.1:$1/" 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

# 只杀真正占口的进程：按 -State Listen 的 OwningProcess 杀（TimeWait 的 OwningProcess=0）
kill_listen() {
  local pids
  pids="$(netstat -ano 2>/dev/null | awk -v p="$1:" '$2 ~ p && $4 == "LISTENING" {print $5}' | sort -u)"
  for p in $pids; do
    [ "$p" != "0" ] && taskkill //F //PID "$p" >/dev/null 2>&1 || true
  done
}

echo "[dual] 收掉旧后端（$BACKEND_PORT / $BOOST_PORT）" >> "$LOG"
kill_listen "$BACKEND_PORT"
kill_listen "$BOOST_PORT"
sleep 3

echo "[dual] 起普通后端 $BACKEND_PORT" >> "$LOG"
"$JDK" -jar "$JAR" --server.port="$BACKEND_PORT" >> "$LOGDIR/dual-backend.log" 2>&1 &
if ! wait_up "$BACKEND_PORT"; then
  echo "[dual] 普通后端没起来（探活失败）" >> "$LOG"
  echo "[dual] DONE" >> "$LOG"
  exit 2
fi
echo "[dual] 普通后端就绪" >> "$LOG"

echo "[dual] 起提速档后端 $BOOST_PORT" >> "$LOG"
IRONOATH_DEV_CITY_LEVEL=16 \
IRONOATH_DEV_START_AMOUNT=2000000 \
SPRING_PROFILES_ACTIVE=dev \
  "$JDK" -jar "$JAR" --server.port="$BOOST_PORT" >> "$LOGDIR/dual-boost.log" 2>&1 &
if wait_up "$BOOST_PORT"; then
  echo "[dual] 提速档后端就绪" >> "$LOG"
else
  # ⚠️ 提速档后端没起来**不直接失败**：继续跑，让那几份照旧报前提不足退 2
  # （那是环境造成的，不是功能红），但要在日志里写明。
  echo "[dual] 提速档后端没起来（探活失败）—— nation 两份会报前提不足退 2" >> "$LOG"
fi

echo "[dual] 开始批跑" >> "$LOG"
BACKEND="http://127.0.0.1:$BACKEND_PORT" \
BOOST_BACKEND="http://127.0.0.1:$BOOST_PORT" \
RUNTIME_PROBES_TIMEOUT="$TIMEOUT" \
RUNTIME_PROBES_LOGDIR="$LOGDIR" \
  bash scripts/run-runtime-probes.sh "$@" >> "$LOG" 2>&1
echo "BATCH_EXIT=$?" >> "$LOG"

kill_listen "$BACKEND_PORT"
kill_listen "$BOOST_PORT"
sleep 2
echo "[dual] 后端已收" >> "$LOG"
echo "[dual] DONE" >> "$LOG"