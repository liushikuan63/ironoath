#!/usr/bin/env bash
# 职责：把 `tools/verify-*-runtime.mjs` 这批运行时量具**逐一实跑一遍**，每份的退出码单独记账。
# 依赖：node、已启动的 dev 后端、已构建的 `client/build/web-mobile`。
#
# 为什么值得留成脚本（台账 #415）：这批量具没挂进 `check.sh`（它们要后端与产物），# 于是"改了一处公共代码之后还有没有跑挂"这件事只能靠人记着逐份敲。# 每份的 env 变量名各不相同（`BACKEND_ORIGIN` / `X_BACKEND` / `X_PROBE_PORT`），脚本按文件自己声明的读，# 端口按序号错开，避免同机并发撞端口。
# **每份退出码显式写进日志**：后台任务通知里的 exit 0 属于整条命令链，不能当判据。
#
# 用法：BACKEND=http://localhost:8199 bash scripts/run-runtime-probes.sh [文件清单]
set -u
cd "$(dirname "$0")/.."
i=0
OUT="${RUNTIME_PROBES_OUT:-"$OUT"}"
: > "$OUT"
while read -r f; do
  i=$((i + 1))
  base="$(basename "$f")"
  case "$base" in
    verify-label-fit-runtime.mjs) continue ;;   # 这一份每格都在跑，不必重复
    verify-art-runtime.mjs)
      echo "SKIP $base 需要 ART_VERIFY_OPS_TOKEN（凭据，不代填）" | tee -a "$OUT"
      continue ;;
  esac
  backend_env="$(grep -oE 'process\.env\.[A-Z_]*BACKEND[A-Z_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  port_env="$(grep -oE 'process\.env\.[A-Z_]*PORT[A-Z_]*' "$f" | head -1 | sed 's/process\.env\.//')"
  [ -z "$backend_env" ] && backend_env="BACKEND_ORIGIN"
  [ -z "$port_env" ] && port_env="PROBE_PORT"
  port=$((8200 + i))
  log="${RUNTIME_PROBES_LOGDIR:-/d/tmp}/probe-$base.log"
  env "$backend_env=http://localhost:8199" "$port_env=$port" node "$f" > "$log" 2>&1
  code=$?
  echo "$code $base ($backend_env, port $port)" | tee -a "$OUT"
done < "${1:-<(ls tools/verify-*-runtime.mjs)}"
echo "--- 汇总：非零退出的份数 = $(grep -cvE '^0 ' "$OUT")"
grep -vE '^0 ' "$OUT" || true
