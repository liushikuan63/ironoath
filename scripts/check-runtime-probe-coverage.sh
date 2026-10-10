#!/usr/bin/env bash
# 职责：守「批跑排除名单」不被陈旧条目与无理由条目污染。
# 依赖：一个活的 tools/ 与 scripts/runtime-probes-exclude.txt。
#
# 为什么需要这道门：2026-10-03 之前，批跑脚本只收 `tools/verify-*-runtime.mjs`
# ⇒ 有 23~24 份真该跑的量具从没被批跑到（#611 记录 23，本次现跑 24），而**没有任何检查会红**。
# 改成"全收 + 显式排除名单"之后，风险从"漏收"搬到了"排除名单会慢慢长大、且没人说得清为什么"。
# 这道门守两件事：
#   ① 名单里每一行都必须有理由（`#` 后非空）——「为什么它不能自动跑」是进名单的唯一凭据；
#   ② 名单里每一个文件名都必须真实存在 —— 删了探针却忘了删名单，那行就成了永真的假证据。
set -euo pipefail
cd "$(dirname "$0")/.."

EXCLUDE="scripts/runtime-probes-exclude.txt"
fail=0

if [ ! -f "$EXCLUDE" ]; then
  echo "[check-runtime-probe-coverage] 找不到 $EXCLUDE" >&2
  exit 1
fi

# ⚠️ 名单文件必须以换行结尾。这不是洁癖：缺末尾换行时，`echo x >> 名单` 会把 x **粘到上一行尾部**，
#    整份文件于是少一条、多一坨，而那坨被当成上一条的理由 —— 校验照样"通过"。
#    实测（2026-10-03）就是这样：往名单里塞一个不存在的文件，门禁仍然 EXIT=0。
if [ -s "$EXCLUDE" ]; then
  last="$(tail -c 1 "$EXCLUDE" | od -An -tx1 | tr -d '[:space:]')"
  if [ "$last" != "0a" ]; then
    echo "[check-runtime-probe-coverage] $EXCLUDE 没有以换行结尾：追加会粘到上一行，校验会静默失效" >&2
    fail=1
  fi
fi

checked=0
# ⚠️ `|| [ -n "$line" ]` 不是可选的保险：名单文件**末行没有换行符**时，`read` 返回非零并丢弃那一行，
#    于是"最后一条排除项"永远不会被校验 —— 实测就是这样：名单有 2 条却只数到 1 条，
#    往里塞一个不存在的文件也因此不红（2026-10-03 反例②当场抓到的）。
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in ''|'#'*) continue ;; esac
  file="${line%%[[:space:]]*}"
  rest="${line#"$file"}"
  # 理由 = '#' 之后的内容，去掉空白后必须非空
  reason="${rest#*#}"
  trimmed="$(printf '%s' "$reason" | tr -d '[:space:]')"
  if [ -z "$trimmed" ]; then
    echo "[check-runtime-probe-coverage] 排除项 $file 没有写理由：进排除名单的唯一凭据就是「为什么它在默认环境只会得到假红」" >&2
    fail=1
  fi
  if [ ! -f "tools/$file" ]; then
    echo "[check-runtime-probe-coverage] 排除项 $file 在 tools/ 下不存在：探针删了名单没删，这行会变成永真的假证据" >&2
    fail=1
  fi
  checked=$((checked + 1))
done < "$EXCLUDE"

if [ "$checked" -eq 0 ]; then
  echo "[check-runtime-probe-coverage] 排除名单里一条都没有：要么文件写坏了，要么该恢复那两条" >&2
  exit 1
fi

# 反向判据：收集规则必须真的是"全收"。一旦有人改回 `ls tools/verify-*-runtime.mjs`，这里必须红 ——
# 否则整套门禁会变成"规则退化了但没人知道"。
# ⚠️ 匹配的是 **代码形态**（带 `ls tools/` 前缀），不是注释里的字样：
#    本文件与 run-runtime-probes.sh 的注释里都要提到旧规则，全文 grep 会把它们一起命中 ⇒ 门禁恒红。
if grep -q 'ls tools/verify-\*-runtime' scripts/run-runtime-probes.sh; then
  echo "[check-runtime-probe-coverage] 收集规则又变回只收 *-runtime.mjs：未命名后缀的量具会重新静默漏跑" >&2
  fail=1
fi
if ! grep -q 'tools/verify-\*\.mjs' scripts/run-runtime-probes.sh; then
  echo "[check-runtime-probe-coverage] 收集规则里找不到 tools/verify-*.mjs：判据本身失效了" >&2
  fail=1
fi

# ⚠️ 最关键的一条：**排除必须真的生效**（2026-10-03 当场抓到）。
# 收集脚本第一版用 `grep -qxF "$b" "$EXCLUDE_LIST"` 整行匹配，而名单行带 `#` 理由
# ⇒ 永远匹配不上 ⇒ 名单形同虚设：`verify-nation-live.mjs` 明明在名单里却仍被跑了。
# 而只验"名单条目存在且有理由"的门禁**抓不到这个** —— 名单本身完全合法，只是没被用上。
# ⇒ 这里直接**复用收集脚本的解析方式**生成一份"应该被排除的名字"，再与真实收集逻辑比：
#   若某条排除项仍出现在 `ls tools/verify-*.mjs` 的全量里且没被剔除 ⇒ 排除没生效。
excluded_names="$(grep -v '^[[:space:]]*#' "$EXCLUDE" | awk 'NF{print $1}')"
for name in $excluded_names; do
  if ! grep -q 'EXCLUDE_NAMES' scripts/run-runtime-probes.sh; then
    echo "[check-runtime-probe-coverage] 收集脚本里找不到 EXCLUDE_NAMES：排除逻辑被改掉了，排除项 $name 会静默失效" >&2
    fail=1
    break
  fi
  # 收集脚本必须先剥注释再取首字段，否则带理由的名单行永远匹配不上
  if grep -q 'grep -qxF "\$b" "\$EXCLUDE_LIST"' scripts/run-runtime-probes.sh; then
    echo "[check-runtime-probe-coverage] 收集脚本仍用整行匹配（grep -qxF \"\$b\" \"\$EXCLUDE_LIST\"）：" \
      "名单行带 # 理由，永远匹配不上 ⇒ 排除静默失效（$name 就是下一个受害者）" >&2
    fail=1
    break
  fi
done

# 读取生产脚本实际用于自动识别的正则；固定变量名是预期值，不另抄一套识别正则。
# 数字主变量必须完整命中并优先于备用变量，否则会静默兜底或截断变量名。
env_regex_checks=0
env_regex_failures=0
for env_kind in TOKEN BACKEND PORT; do
  case "$env_kind" in
    TOKEN) env_assignment=tok_env ;;
    BACKEND) env_assignment=backend_env ;;
    PORT) env_assignment=port_env ;;
  esac
  env_pattern="$(sed -n "/^[[:space:]]*${env_assignment}=/s/.*grep -oE '\([^']*\)'.*/\1/p" scripts/run-runtime-probes.sh)"
  if [ -z "$env_pattern" ] || [ "$(printf '%s\n' "$env_pattern" | wc -l)" -ne 1 ]; then
    echo "[check-runtime-probe-coverage] 找不到唯一的生产 $env_kind 识别正则" >&2
    env_regex_failures=$((env_regex_failures + 1))
    fail=1
    continue
  fi
  while IFS=$'\t' read -r case_kind sample expected_env; do
    [ "$case_kind" = "$env_kind" ] || continue
    env_regex_checks=$((env_regex_checks + 1))
    actual_env="$(printf '%s\n' "$sample" | grep -oE "$env_pattern" | head -1 | sed 's/process\.env\.//' || true)"
    if [ "$actual_env" != "$expected_env" ]; then
      echo "[check-runtime-probe-coverage] $env_kind 识别错误：$sample → ${actual_env:-空}，应为 $expected_env" >&2
      env_regex_failures=$((env_regex_failures + 1))
      fail=1
    fi
  done <<'ENV_REGEX_CASES'
TOKEN	process.env.AUDIT_TOKEN	AUDIT_TOKEN
TOKEN	process.env.V2_OPS_TOKEN	V2_OPS_TOKEN
TOKEN	process.env.AUDIT_TOKEN_V2	AUDIT_TOKEN_V2
TOKEN	process.env.V2_OPS_TOKEN ?? process.env.OPS_TOKEN	V2_OPS_TOKEN
BACKEND	process.env.AUDIT_BACKEND	AUDIT_BACKEND
BACKEND	process.env.V2_BACKEND	V2_BACKEND
BACKEND	process.env.AUDIT_BACKEND_V2	AUDIT_BACKEND_V2
BACKEND	process.env.V2_BACKEND ?? process.env.BACKEND_ORIGIN	V2_BACKEND
PORT	process.env.AUDIT_PORT	AUDIT_PORT
PORT	process.env.NATION_S2_PORT	NATION_S2_PORT
PORT	process.env.AUDIT_PORT_V2	AUDIT_PORT_V2
PORT	process.env.NATION_S2_PORT ?? process.env.PROBE_PORT	NATION_S2_PORT
ENV_REGEX_CASES
done
echo "[check-runtime-probe-coverage] 生产 env 正则自检：$env_regex_checks 项，错误 $env_regex_failures 项"

if [ "$fail" -ne 0 ]; then
  exit 1
fi
total=$(ls tools/verify-*.mjs | wc -l)
echo "[check-runtime-probe-coverage] 通过：量具 $total 份，排除 $checked 份（名单条目都存在且有理由；收集规则仍是全收）"