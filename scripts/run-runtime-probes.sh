#!/usr/bin/env bash
# 职责：把 `tools/verify-*-runtime.mjs` 这批运行时量具**逐一实跑一遍**，每份的退出码单独记账。
# 依赖：node、已启动的 dev 后端、已构建的 `client/build/web-mobile`。
#
# 为什么值得留成脚本（台账 #415）：这批量具没挂进 `check.sh`（它们要后端与产物），# 于是"改了一处公共代码之后还有没有跑挂"这件事只能靠人记着逐份敲。# 每份的 env 变量名各不相同（`BACKEND_ORIGIN` / `X_BACKEND` / `X_PROBE_PORT`），脚本按文件自己声明的读，# 端口按序号错开，避免同机并发撞端口。
# **每份退出码显式写进日志**：后台任务通知里的 exit 0 属于整条命令链，不能当判据。
#
# 用法：BACKEND=http://localhost:8199 bash scripts/run-runtime-probes.sh [文件清单]
#   可选分流：BOOST_BACKEND + BOOST_PROBES（提速档那两份）；WAR_BACKEND + WAR_PROBES（真仗档，默认
#     只分流 `verify-war-real-battle`）；OPS_TOKEN_VALUE（批跑入口自 mint 的运维令牌，注入给
#     声明了 `*_OPS_TOKEN` 的探针 —— 三者都不传时行为与改动前完全一致）。
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
EXCLUDE_LIST="${RUNTIME_PROBES_EXCLUDE:-scripts/runtime-probes-exclude.txt}"
if [ -z "$LIST" ]; then
  LIST="$(mktemp -t runtime-probes-list.XXXXXX)"
  # 收集**全部** verify-*.mjs（2026-10-03，#753 收尾）：旧规则只收 `*-runtime.mjs`，
  # 而"文件名带不带 -runtime"与"要不要真跑"毫无关系 —— #611 实测有 23 份量具从没被批跑到
  # （含整个 nation 族），本次现跑是 **24/24 份都需要后端、23 份开浏览器、0 份纯逻辑**，
  # 即它们全都是该真跑的量具，只是没按命名约定起名。
  # ⇒ 唯一还需要手工表达的是"**这几份在默认环境跑只会得到假红**"，那就是 $EXCLUDE_LIST。
  # ⚠️ 名单的行**带 `#` 理由**，所以不能用 `grep -qxF "$b"` 整行匹配 ——
  #    那样永远匹配不上，**排除名单会静默失效**（2026-10-03 实测：`verify-nation-live.mjs`
  #    明明写在名单里却仍被跑了，账本里 `port 8234` 那一行就是它）。
  #    ⇒ 先剥掉注释、只留每行第一个字段，再比文件名。
  EXCLUDE_NAMES="$(grep -v '^[[:space:]]*#' "$EXCLUDE_LIST" 2>/dev/null | awk 'NF{print $1}')"
  ls tools/verify-*.mjs \
    | while read -r p; do
        b="$(basename "$p")"
        if [ -n "$EXCLUDE_NAMES" ] && printf '%s\n' "$EXCLUDE_NAMES" | grep -qxF "$b"; then
          echo "EXCLUDE $b （见 $EXCLUDE_LIST 的理由）" >&2
        else
          echo "$p"
        fi
      done > "$LIST"
fi
# 归档上一批的日志（#753 收尾，2026-10-03）：每份探针的日志写成**固定文件名** `probe-<name>.log`，
# 于是每一批都会把上一批覆盖掉 —— 批跑里出现的偶发红，事后证据就没了，连"重现"都不干净
# （探针自己还会 POST /tech/research、/city/cancel 改状态）。实测踩到：一次 `23 过 / 29 败`
# 的异常读数就是这么丢的，直到发现时已经无法复盘。
# ⇒ 每批开跑前把上一批整体搬进 `archive/<时间戳>/`。**刻意不改日志文件名** ——
#   各门禁与文档都引用 `$RUNTIME_PROBES_LOGDIR/probe-*.log` 这个路径，改名会连带一堆引用。
# ⚠️ `set -u` 下必须写 ${KEEP_PREV_LOGS:-}：不设这个变量时直接引用会报
#    "KEEP_PREV_LOGS: unbound variable" 让整份脚本崩（2026-10-03 踩到两次：
#    第一次写这一段时正例与反向对照**都显式传了变量**，从没测到默认路径；
#    第二次是用户名 a1740→Admin 之后那次提交丢失，同一处又退回未修形态）。
if [ "${KEEP_PREV_LOGS:-}" != "1" ]; then
  _prev="$(ls "${RUNTIME_PROBES_LOGDIR:-/d/tmp}"/probe-*.log 2>/dev/null | wc -l)"
  if [ "$_prev" -gt 0 ]; then
    _stamp="$(date +%Y%m%d-%H%M%S)"
    _dir="${RUNTIME_PROBES_LOGDIR:-/d/tmp}/archive/$_stamp"
    mkdir -p "$_dir"
    mv "${RUNTIME_PROBES_LOGDIR:-/d/tmp}"/probe-*.log "$_dir/" 2>/dev/null
    echo "# 上一批 $_prev 份日志已归档到 $_dir（KEEP_PREV_LOGS=1 可关掉归档）" >&2
  fi
fi
: > "$OUT"
# 把"这一批打的是哪台后端"写进记录：`BACKEND` 有默认值（8199＝dev 约定），
# 但默认值一旦没落进输出，事后就分不清这批读数是哪个端机器给的（同族教训：静默回落 8080）
echo "# 后端=$BACKEND 清单=$LIST 记账=$OUT" | tee -a "$OUT"
while read -r f; do
  # 显式清单也会由 Windows 工具写成 CRLF；read 会保留行尾 CR，导致真实文件被当成不存在。
  # 只规范化行尾，不改路径正文，否则整批会得到 MODULE_NOT_FOUND / NO-RUN 而没有功能读数。
  f="${f%$'\r'}"
  i=$((i + 1))
  base="$(basename "$f")"
  # 排除名单已在生成 $LIST 时过滤过（见上）；这里保留 case 只为兼容**显式传入** LIST 的旧用法。
  case "$base" in
    verify-label-fit-runtime.mjs) continue ;;   # 这一份每格都在跑，不必重复
  esac
  # 凭据这一族（2026-10-07 重写谓词，取证见 收口清单 #776）。
  # 旧写法 `[ -z "${!tok_env:-}" ]` 只看"shell 里有没有那个变量名"，**不看探针自带的默认值**，
  # 于是 7 份里有 5 份（源码写了 `?? 'art-verify-local'`）常年被记成 SKIP —— 而它们本来就能跑：
  # `scripts/verify-runtime.sh:18` 早就在用同一个串给本地后端自 mint ops token。
  # 新判据三段，顺序固定：
  #   ① shell 里已经显式设了那个变量 ⇒ 用它（人的意图最大）；
  #   ② 本轮由批跑入口 mint 了 `OPS_TOKEN_VALUE` ⇒ 注入它。**必须注入而不是让探针用自己的默认值**：
  #      两边各自默认而没人对齐时，症状是 `/ops/*` 全片 1009，一片红看着像功能坏了；
  #   ③ 两者都没有 ⇒ 记 **NO-RUN 凭据**（这台后端的 token 我无从得知 ⇒ 与"红"和"量具没起来"分开计，
  #      绝不记成 SKIP 混进通过率，也绝不代填）。
  # ⚠️ ③ 里"探针自带 `?? 'art-verify-local'` 默认值"**不作为放行的理由**：单后端入口的 `BACKEND`
  #    可以指向任何一台后端（包括别人起的、令牌不是那个串的后端），照跑得到的是一片 1009 假红。
  #    默认值只在"这批的后端是本脚本起的、令牌是脚本 mint 的"这一条路上才成立 —— 那条路由 ② 覆盖。
  tok_env="$(grep -oE 'process\.env\.[A-Z0-9_]*TOKEN[A-Z0-9_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  tok_assign=""
  if [ -n "$tok_env" ]; then
    tok_value="${!tok_env:-}"
    [ -z "$tok_value" ] && tok_value="${OPS_TOKEN_VALUE:-}"
    if [ -z "$tok_value" ]; then
      echo "NO-RUN $base 缺凭据 $tok_env（探针要的令牌没有来源：既没设 $tok_env，本轮批跑也没 mint ⇒ 这一份没跑成，不是红）" | tee -a "$OUT"
      continue
    fi
    tok_assign="$tok_env=$tok_value"
  fi
  tok_args=()
  [ -n "$tok_assign" ] && tok_args+=("$tok_assign")
  backend_env="$(grep -oE 'process\.env\.[A-Z0-9_]*BACKEND[A-Z0-9_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  port_env="$(grep -oE 'process\.env\.[A-Z0-9_]*PORT[A-Z0-9_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  [ -z "$backend_env" ] && backend_env="BACKEND_ORIGIN"
  [ -z "$port_env" ] && port_env="PROBE_PORT"
  # 2026-10-05：**dev 提速档是要设在「后端进程」上的**（`IRONOATH_DEV_CITY_LEVEL=16` +
  # `IRONOATH_DEV_START_AMOUNT=2000000`，只在 dev profile 生效），探针侧设了没用。
  # ⇒ 给这几份单独指一台提速档后端：`BOOST_BACKEND`（可空）+ `BOOST_PROBES`（正则，默认覆盖 nation 三份）。
  # `verify-nation-rally-runtime`（V22-c）也在里面：它要**建国**（主城 16 级），而它**不能**去真仗档那一台 ——
  # 那一台带 `IRONOATH_DEV_TIME_SPEED`，会把"练兵到齐"与"准备窗口还开着"这两段时间前提一起改掉。
  # 不配 `BOOST_BACKEND` ⇒ 行为与改动前完全一致（仍打 `$BACKEND`）。
  target_backend="$BACKEND"
  if [ -n "${BOOST_BACKEND:-}" ]; then
    boost_re="${BOOST_PROBES:-verify-nation-live|verify-nation-policy-ui|verify-nation-rally-runtime|verify-level-reward-runtime}"
    if printf '%s' "$base" | grep -qE "$boost_re"; then
      target_backend="$BOOST_BACKEND"
    fi
  fi
  # 2026-10-07 加：**真仗档**（第三台后端）。`verify-war-real-battle` 要的提速是
  # `IRONOATH_DEV_TIME_SPEED=100`（后端进程的环境变量，不是探针侧的，探针自己量倍速、<50× 就退 2），
  # 而**这个倍速会改掉 nation 那两份的时间前提**（练兵、行军、3 小时国战窗口全走同一套 serverNow）
  # ⇒ 不能复用提速档那一台，另起一台专给它（由 `run-batch-dual-backend.sh` 负责起与收）。
  # 不传 `WAR_BACKEND` ⇒ 这一整段与改动前完全一致（真仗那份照旧打 `$BACKEND`，自己退 2 说前提不足）。
  if [ -n "${WAR_BACKEND:-}" ]; then
    war_re="${WAR_PROBES:-verify-war-real-battle}"
    if printf '%s' "$base" | grep -qE "$war_re"; then
      target_backend="$WAR_BACKEND"
    fi
  fi
  port=$((8200 + i))
  logdir="${RUNTIME_PROBES_LOGDIR:-/d/tmp}"
  mkdir -p "$logdir"
  log="$logdir/probe-$base.log"
  run_probe() {   # $1=端口 $2=日志文件；**把退出码 echo 到 stdout**（调用方用 $(…) 取），超时 echo 124
    # 令牌用数组传，不用裸字符串：token 里只要有一个空格，未加引号的展开就会被拆成两个 `env` 参数，
    # 第二个会被当成**另一个变量赋值**塞进子进程环境 —— 那是自己给自己开的一条注入面（bash 5.3 下空数组安全）
    env "$backend_env=$target_backend" "$port_env=$1" "${tok_args[@]}" node "$f" > "$2" 2>&1 &
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
  # 2026-10-04 加：**日志非空，也可能是"量具自己没起来"**。启动期错误（模块找不到、前端产物缺失）
  # 会往日志里写满 stack trace ⇒ 旧判据下它被记成一次普通的红，**冒充产品缺陷**。
  # 实测（新机器上从零装环境那次）：探针写死的 playwright 绝对路径失效 ⇒ 所有浏览器类探针一起判红，
  # 而根因与环境有关、与产品无关；同一批里 `client/build/web-mobile` 缺失也走同一条路。
  # ⇒ 这两类"没跑起来"的形状与"日志空"同义，一并记 NO-RUN，让它与真红可分辨。
  elif grep -qE 'ERR_MODULE_NOT_FOUND|Cannot find module|MODULE_NOT_FOUND' "$log" \
    || grep -qE 'ENOENT.*index\.html' "$log"; then
    code="NO-RUN"
  fi
  # 退出码 2 = **探针自己声明的「量具前提不足」**（本仓约定，见 `verify-social-create-runtime` A 相那类：
  # 读到等级不对就退 2 并说明"这一份要跑在不带提速档的后端上"）。
  # 它既不是产品红，也不是"量具没起来"的 NO-RUN，而是"这台机器/这个后端不满足我跑的前提"。
  # 2026-10-04 实测踩到：`verify-panel-reachability` 退 2 并写明「量具未校准，读数作废」，
  # 而旧汇总只放过 `0 `/`SKIP `/`TIMEOUT `/`# ` ⇒ 它被算进「需看的份数」，还白跑一次 RERUN
  # （补跑解决不了"前提没架对"，那不是噪声，是确定性红）。
  # ⇒ 单独归一类 PREREQ：不进 RERUN、不进"需看"、不与 TIMEOUT 混在一起列表。
  [ "$code" = "2" ] && code="PREREQ"
  # 有的量具拿"计时对上预算"当判据，而那条预算正落在它自己的读数散布里（#444 实测：perf 首屏
  # 同一颗 SHA 四跑 2715/2830/3234/3244 对预算 3000）—— 超一次不构成缺陷。所以非零时补跑一次，
  # **两次都超才判红**：真退化会连红两次，于是这不是把阈值挪走，只是不让噪声冒充缺陷。
  # 首跑 NO-RUN 不补跑（那是环境没起来，补跑只会多一个假数）。
  # 超时不补跑：再跑一次只会再挂 10 分钟（首跑 NO-RUN 同理）
  # PREREQ 不补跑：前提没架对，补跑必然同样退 2，白等一个超时窗口
  if [ "$code" != "0" ] && [ "$code" != "NO-RUN" ] && [ "$code" != "TIMEOUT" ] && [ "$code" != "PREREQ" ]; then
    code2=$(run_probe "$((port + 100))" "$log.retry")
    [ -s "$log.retry" ] || code2="NO-RUN"
    # 这行必须带 `#` 前缀：汇总按 `^(0 |SKIP |# )` 排除元信息，换个词就被数成一次红。
    echo "# RERUN $base 首跑=$code 复跑=$code2 判定取复跑" | tee -a "$OUT"
    code="$code2"
  fi
  echo "$code $base ($backend_env, port $port)" | tee -a "$OUT"
done < "$LIST"
# SKIP 不是红、`#` 开头的是本批的元信息行、超时与 PREREQ 另列（见末尾那两行）：
# 这张表只数"真正需要人看、且不是超时/前提不足"的那些。
# ⚠️ 措辞用"需看的份数"而不是"非零退出的份数"（2026-10-04）：NO-RUN 与 PREREQ 都**没有可用的退出码**
# （前者是量具没起来，后者是量具声明前提不足），旧措辞把它们算进"非零退出"会让人以为量具测出了红。
echo "--- 汇总：需看的份数 = $(grep -cvE '^(0 |SKIP |TIMEOUT |PREREQ |# )' "$OUT")（其中 未跑成 NO-RUN = $(grep -c '^NO-RUN ' "$OUT")，再其中 缺凭据 = $(grep -c '^NO-RUN .*缺凭据' "$OUT")）  超时 = $(grep -c '^TIMEOUT ' "$OUT")  前提不足 PREREQ = $(grep -c '^PREREQ ' "$OUT")  SKIP = $(grep -c '^SKIP ' "$OUT")"
grep -vE '^(0 |SKIP |TIMEOUT |PREREQ |# )' "$OUT" || true
# 超时与"前提不足"各自单独列一遍：它们不在上面那张表里，但恰恰是最需要人看的一批
grep '^TIMEOUT ' "$OUT" || true
grep '^PREREQ ' "$OUT" || true
