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
#   可覆盖：BOOST_PORT（默认 8198）· BACKEND_PORT（默认 8199）· WAR_PORT（默认 8197，真仗档）
#           WAR_TIME_SPEED（默认 100，写进真仗档后端的 IRONOATH_DEV_TIME_SPEED）
#           RUNTIME_OPS_TOKEN（默认 art-verify-local —— **本批自 mint 的临时令牌，不是凭据**，
#                            同时传给三台后端的 --ironoath.ops.token 与探针的 *_OPS_TOKEN）
#           RUNTIME_PROBES_TIMEOUT（默认 900）· RUNTIME_PROBES_LOGDIR（默认 /d/tmp）
#           LOG（默认 /d/tmp/probe753/batch-dual.log）
#
# 现在起的是**三台**后端（2026-10-07 加第三台）：
#   8199 普通 · 8198 提速档（城 16 级 + 起始资源，给 nation 两份） · 8197 真仗档（再带 TIME_SPEED=100，
#   给 verify-war-real-battle —— 它要快速推进练兵/行军/3 小时国战窗口，而这个倍速会改掉 nation 两份的
#   时间前提，所以不能与 8198 共用一台）。
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
# 真仗档（第三台）：见下面 "起真仗档后端" 那段 —— 它带的 IRONOATH_DEV_TIME_SPEED 是**后端进程**的变量，
# 与提速档那两枚同族，但会改掉 nation 两份的时间前提，所以**不复用 8198**，单独一台。
WAR_PORT="${WAR_PORT:-8197}"
TIMEOUT="${RUNTIME_PROBES_TIMEOUT:-900}"
LOG="${LOG:-/d/tmp/probe753/batch-dual.log}"
LOGDIR="${RUNTIME_PROBES_LOGDIR:-/d/tmp}"
JDK="/d/Java/jdk/microsoft-jdk-17/bin/java.exe"
# jar 可指向 worktree 沙箱里刚重打的那一份（`GAME_WEB_JAR=../ironoath-sbx-*/server/game-web/target/game-web.jar`）。
# 为什么要有这个旋钮：主树那份 `target/game-web.jar` 经常是**旧的**（跑依赖新契约列的探针会得到
# "那一列静默不存在"的假红，实测见 收口清单 #773），而它同时是另一条会话活 JVM 的文件句柄 ——
# 不能为了换新就把它覆盖掉。
JAR="${GAME_WEB_JAR:-server/game-web/target/game-web.jar}"
# 本地批跑自 mint 的运维令牌：不是凭据，是**这一批自己发的临时值**，与 `scripts/verify-runtime.sh:18`
# 用的是同一个变量名与同一个默认串（取证与三段判据见 run-runtime-probes.sh 的 token 注释、收口清单 #776）。
# ⚠️ 只在"后端是本脚本起的"时成立 —— 打到别人的后端时我们无从知道它的令牌，那时探针会记 NO-RUN 而不是红。
OPS_TOK="${RUNTIME_OPS_TOKEN:-art-verify-local}"

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

# 端口是否还有人听 —— 用 /dev/tcp 秒级探一下（`wait_up` 那条要 70 秒，不能拿来复验清理）
port_open() { (echo > "/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# 只杀真正占口的进程：按 -State Listen 的 OwningProcess 杀（TimeWait 的 OwningProcess=0）
# ⚠️ 判据必须是 `":<端口>$"`（端口在 local-address 字段的**末尾**，后面没有冒号）——
#    原先写的是 `$2 ~ "8199:"`，netstat 给的是 `0.0.0.0:8199` / `[::]:8199`，**永远匹配不上**，
#    于是这个函数一直是空转、日志却照样打"[dual] 后端已收"（2026-10-07 实测：一轮批跑后
#    三台 JVM 全活着，旧判据命中 0 条、新判据命中 3 条 PID ⇒ 同刻对照）。
#    后果就是本文件上面自己写过的那条：活 JVM 持着 game-web.jar ⇒ `scripts/build.sh` 报
#    'Unable to rename …' ⇒ 下一位会以为是自己编译坏了。
kill_listen() {
  local p="$1" pids
  pids="$(netstat -ano 2>/dev/null | awk -v re=":${p}\$" '$2 ~ re && $4 == "LISTENING" {print $5}' | sort -u)"
  for q in $pids; do
    [ "$q" != "0" ] && taskkill //F //PID "$q" >/dev/null 2>&1 || true
  done
  # 收完必须**复验**：不验的话"清理"这件事本身就是一个不可失败的断言（上面那个空转判据就是这么活下来的）
  sleep 2
  if port_open "$p"; then
    echo "[dual] ⚠️ 端口 $p 仍有后端在听（清理没生效）：PID=$(netstat -ano 2>/dev/null | awk -v re=":${p}\$" '$2 ~ re && $4 == "LISTENING" {print $5}' | sort -u | tr '\n' ' ')" >> "$LOG"
    return 1
  fi
  return 0
}

# 2026-10-05 17:20x 补：**报告**那些「在跑 game-web 但不占本脚本两个端口」的后端 JVM。
# ⚠️ **只报告、不杀** —— 那些进程是**别人（或脚本之外的会话）起的**，
#    本脚本没有理由替别人杀（那会波及别的会话正在跑的批跑）。
# ⚠️ **为什么必须报告**：这类残留会让后面的 `scripts/build.sh` 失败
#    —— `spring-boot-maven-plugin:repackage` 要 rename `game-web.jar`，
#    而**只要有 JVM 占着那个 jar 就 rename 不动**（实测 `Unable to rename …`）。
#    ⚠️ 而且它们**按端口查不出来**（不占 8199/8198）⇒ 只能按命令行扫。
report_stray_backends() {
  local stray
  stray="$(powershell.exe -NoProfile -Command \
    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*game-web.jar*' } | ForEach-Object { \$_.ProcessId }" 2>/dev/null | tr -d '\r')"
  if [ -n "$stray" ]; then
    echo "[dual] ⚠️ 另有 game-web 后端在跑（pid: $(echo "$stray" | tr '\n' ' ')）——不是本脚本起的，不代杀" >> "$LOG"
    echo "[dual] ⚠️ 它们会占着 server/game-web/target/game-web.jar ⇒ 之后跑 scripts/build.sh 会报" >> "$LOG"
    echo "[dual]    'Unable to rename …' ⇒ 需要先自己停掉它们" >> "$LOG"
  fi
}

echo "[dual] 收掉旧后端（$BACKEND_PORT / $BOOST_PORT / $WAR_PORT）" >> "$LOG"
kill_listen "$BACKEND_PORT"
kill_listen "$BOOST_PORT"
kill_listen "$WAR_PORT"
report_stray_backends
sleep 3

echo "[dual] 起普通后端 $BACKEND_PORT" >> "$LOG"
"$JDK" -jar "$JAR" --server.port="$BACKEND_PORT" --ironoath.ops.token="$OPS_TOK" >> "$LOGDIR/dual-backend.log" 2>&1 &
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
  "$JDK" -jar "$JAR" --server.port="$BOOST_PORT" --ironoath.ops.token="$OPS_TOK" >> "$LOGDIR/dual-boost.log" 2>&1 &
if wait_up "$BOOST_PORT"; then
  echo "[dual] 提速档后端就绪" >> "$LOG"
else
  # ⚠️ 提速档后端没起来**不直接失败**：继续跑，让那几份照旧报前提不足退 2
  # （那是环境造成的，不是功能红），但要在日志里写明。
  echo "[dual] 提速档后端没起来（探活失败）—— nation 两份会报前提不足退 2" >> "$LOG"
fi

# ---------- 第三台：真仗档（2026-10-07，裁决「加第三台后端」）----------
# 为什么必须单独一台，而不是给上面的提速档加一个开关：`IRONOATH_DEV_TIME_SPEED` 改的是
# **整个 serverNow 的推进速度**（练兵 60s×数量、行军、3 小时国战窗口全走同一套时钟），
# 一打开就会改掉 `verify-nation-live` / `verify-nation-policy-ui` 那两份的时间前提 ⇒
# 同一批里"要慢时钟的"与"要快时钟的"不能共用一台。真仗探针自己会量实测倍速，
# **低于 50× 就退 2 并说"量具没架对"**，所以这里的默认值必须是 100（不是可选装饰）。
echo "[dual] 起真仗档后端 $WAR_PORT（TIME_SPEED=${WAR_TIME_SPEED:-100}）" >> "$LOG"
IRONOATH_DEV_CITY_LEVEL=16 \
IRONOATH_DEV_START_AMOUNT=2000000 \
IRONOATH_DEV_TIME_SPEED="${WAR_TIME_SPEED:-100}" \
SPRING_PROFILES_ACTIVE=dev \
  "$JDK" -jar "$JAR" --server.port="$WAR_PORT" --ironoath.ops.token="$OPS_TOK" >> "$LOGDIR/dual-war.log" 2>&1 &
if wait_up "$WAR_PORT"; then
  echo "[dual] 真仗档后端就绪" >> "$LOG"
else
  # 同上：起不来就让真仗那份自己退 2（PREREQ），不拖整批
  echo "[dual] 真仗档后端没起来（探活失败）—— verify-war-real-battle 会报前提不足退 2" >> "$LOG"
fi

echo "[dual] 开始批跑" >> "$LOG"
BACKEND="http://127.0.0.1:$BACKEND_PORT" \
BOOST_BACKEND="http://127.0.0.1:$BOOST_PORT" \
WAR_BACKEND="http://127.0.0.1:$WAR_PORT" \
OPS_TOKEN_VALUE="$OPS_TOK" \
RUNTIME_PROBES_TIMEOUT="$TIMEOUT" \
RUNTIME_PROBES_LOGDIR="$LOGDIR" \
  bash scripts/run-runtime-probes.sh "$@" >> "$LOG" 2>&1
echo "BATCH_EXIT=$?" >> "$LOG"

left=''
for p in "$BACKEND_PORT" "$BOOST_PORT" "$WAR_PORT"; do
  kill_listen "$p" || left="$left $p"
done
# "后端已收"这句话**必须由复验说了算**：原先它是无条件打印的，而清理函数其实一直在空转
if [ -z "$left" ]; then
  echo "[dual] 后端已收（三台端口复验过：$BACKEND_PORT / $BOOST_PORT / $WAR_PORT 都不再监听）" >> "$LOG"
else
  echo "[dual] 后端**没有全部收掉**，仍被占着的端口：$left ⇒ 它们会持着 $JAR，之后跑 build.sh 会报 Unable to rename" >> "$LOG"
fi
echo "[dual] DONE" >> "$LOG"