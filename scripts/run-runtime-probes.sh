#!/usr/bin/env bash
# 职责：把 `tools/verify-*-runtime.mjs` 这批运行时量具**逐一实跑一遍**，每份的退出码单独记账。
# 依赖：node、已启动的 dev 后端、已构建的 `client/build/web-mobile`。
#
# 为什么值得留成脚本（台账 #415）：这批量具没挂进 `check.sh`（它们要后端与产物），# 于是"改了一处公共代码之后还有没有跑挂"这件事只能靠人记着逐份敲。# 每份的 env 变量名各不相同（`BACKEND_ORIGIN` / `X_BACKEND` / `X_PROBE_PORT`），脚本按文件自己声明的读，# 端口按序号错开，避免同机并发撞端口。
# **每份退出码显式写进日志**：后台任务通知里的 exit 0 属于整条命令链，不能当判据。
#
# 用法：BACKEND=http://localhost:8199 bash scripts/run-runtime-probes.sh [文件清单]
# 超时：RUNTIME_PROBES_TIMEOUT=<秒>（默认 900，理由见下面 PROBE_TIMEOUT 那段）—— 一份量具卡住不能让
#   整批停摆（2026-09-23 实测：run1 跑到 `verify-perf-runtime` 就 9 分钟不再输出一个字节，只能手动停）。
#   超时记成 TIMEOUT，**与"跑出来是红"分开计**（同 NO-RUN 那条纪律：没跑完不等于验出了问题）。
set -u
cd "$(dirname "$0")/.."
i=0
# 三个默认值都必须自己成立：#416 那版把 `RUNTIME_PROBES_OUT` 的默认写成 `${...:-"$OUT"}`
# （自引用，`set -u` 下无参跑当场 "OUT: unbound variable"），把清单默认写成 `<(...)` 塞进参数展开
# （不是进程替换，是字文件名），于是**无参整批跑这条路从来没通过** —— 当时只验了自己敲过的那条。
BACKEND="${BACKEND:-http://localhost:8199}"
OUT="${RUNTIME_PROBES_OUT:-/d/tmp/runtime-probes-exitcodes.txt}"
LIST="${1:-}"
# 不用 `timeout` 命令：Git Bash 的 PATH 上先命中 System32 的 timeout.exe（行为完全不同，
# 会去等键盘输入），而 CI 的 Linux runner 有 GNU timeout —— 两边不等价。用纯 bash 看门狗。
# 默认 900 秒不是拍脑袋：`verify-perf-runtime.mjs` 的 `PERF_SOAK_SECONDS` 默认就是 600（泡机取样），
# 加启动与收尾要 660 秒以上 —— 默认值若低于它，这份量具**每批都必然假 TIMEOUT**（实测 300 秒时正是如此）。
PROBE_TIMEOUT="${RUNTIME_PROBES_TIMEOUT:-900}"
if [ -z "$LIST" ]; then
  LIST="$(mktemp -t runtime-probes-list.XXXXXX)"
  ls tools/verify-*-runtime.mjs > "$LIST"
fi
: > "$OUT"
# 把"这一批打的是哪台后端"写进记录：`BACKEND` 有默认值（8199＝dev 约定），
# 但默认值一旦没落进输出，事后就分不清这批读数是哪个端机器给的（同族教训：静默回落 8080）
echo "# 后端=$BACKEND 清单=$LIST 记账=$OUT" | tee -a "$OUT"
while read -r f; do
  i=$((i + 1))
  base="$(basename "$f")"
  case "$base" in
    verify-label-fit-runtime.mjs) continue ;;   # 这一份每格都在跑，不必重复
  esac
  # 缺凭据的那几份**按谓词筛**，不按文件名点名（#416 那版只写死了 `verify-art`，
  # 于是同样要令牌的 `verify-devtools` 被记成一次普通的红，汇总里的"非零份数"就不数了）。
  tok_env="$(grep -oE 'process\.env\.[A-Z_]*TOKEN[A-Z_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  if [ -n "$tok_env" ] && [ -z "${!tok_env:-}" ]; then
    echo "SKIP $base 需要 $tok_env（凭据，不代填）" | tee -a "$OUT"
    continue
  fi
  backend_env="$(grep -oE 'process\.env\.[A-Z_]*BACKEND[A-Z_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  port_env="$(grep -oE 'process\.env\.[A-Z_]*PORT[A-Z_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  [ -z "$backend_env" ] && backend_env="BACKEND_ORIGIN"
  [ -z "$port_env" ] && port_env="PROBE_PORT"
  port=$((8200 + i))
  logdir="${RUNTIME_PROBES_LOGDIR:-/d/tmp}"
  mkdir -p "$logdir"
  log="$logdir/probe-$base.log"
  run_probe() {   # $1=端口 $2=日志文件；**把退出码 echo 到 stdout**（调用方用 $(…) 取），超时 echo 124
    env "$backend_env=$BACKEND" "$port_env=$1" node "$f" > "$2" 2>&1 &
    local pid=$! waited=0 code
    while [ "$waited" -lt "$PROBE_TIMEOUT" ]; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 5
      waited=$((waited + 5))
    done
    if kill -0 "$pid" 2>/dev/null; then
      # 只杀 node 本身：Playwright 拉起的 chrome 子进程可能留活口（bash 没有可移植的子树杀法），
      # 所以超时后仍要人看一眼任务管理器 —— 这一点不假装解决
      kill -TERM "$pid" 2>/dev/null; sleep 2; kill -KILL "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
      echo 124
      return
    fi
    wait "$pid"
    code=$?
    echo "$code"
  }
  code=$(run_probe "$port" "$log")
  [ "$code" = "124" ] && code="TIMEOUT"
  # 日志空 = 这一份**根本没跑成**（重定向失败、node 没起来…），它的退出码和被测系统无关。
  # 不标出来的话，"没跑"会被记成一次普通的红或绿 —— #416 那版 just 这么把 `RUNTIME_PROBES_LOGDIR`
  # 指到一个不存在的目录，就得到一个凭空虚记的 "1"。
  if [ ! -s "$log" ]; then
    code="NO-RUN"
  fi
  # 有的量具拿"计时对上预算"当判据，而那条预算正落在它自己的读数散布里（#444 实测：perf 首屏
  # 同一颗 SHA 四跑 2715/2830/3234/3244 对预算 3000）—— 超一次不构成缺陷。所以非零时补跑一次，
  # **两次都超才判红**：真退化会连红两次，于是这不是把阈值挪走，只是不让噪声冒充缺陷。
  # 首跑 NO-RUN 不补跑（那是环境没起来，补跑只会多一个假数）。
  # 超时不补跑：再跑一次只会再挂 10 分钟（首跑 NO-RUN 同理）
  if [ "$code" != "0" ] && [ "$code" != "NO-RUN" ] && [ "$code" != "TIMEOUT" ]; then
    code2=$(run_probe "$((port + 100))" "$log.retry")
    [ -s "$log.retry" ] || code2="NO-RUN"
    # 这行必须带 `#` 前缀：汇总按 `^(0 |SKIP |# )` 排除元信息，换个词就被数成一次红。
    echo "# RERUN $base 首跑=$code 复跑=$code2 判定取复跑" | tee -a "$OUT"
    code="$code2"
  fi
  echo "$code $base ($backend_env, port $port)" | tee -a "$OUT"
done < "$LIST"
# SKIP 不是红、`#` 开头的是本批的元信息行：汇总只数真正跑过而非零的那些
echo "--- 汇总：非零退出的份数 = $(grep -cvE '^(0 |SKIP |TIMEOUT |# )' "$OUT")  超时 = $(grep -c '^TIMEOUT ' "$OUT")  SKIP = $(grep -c '^SKIP ' "$OUT")"
grep -vE '^(0 |SKIP |TIMEOUT |# )' "$OUT" || true
# 超时单独列一遍：它们不在上面那张表里，但恰恰是最需要人看的一批
grep '^TIMEOUT ' "$OUT" || true
