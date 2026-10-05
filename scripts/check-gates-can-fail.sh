#!/usr/bin/env bash
# 门禁「能不能失败」的回归自测（2026-10-05 建）。
#
# 为什么有这道门：`scripts/check.sh` 跑绿**只说明这些门"没触发"**，
# **不说明"该触发时会触发"**。本仓库的门禁从来没有被系统验证过这一条。
# 本会话逐门造过违规，发现「跑绿」与「门在工作」是两件事，于是固化成这道门。
#
# 判据（每条三组读数）：
#   基线绿 → 造违规必须红 → 还原必须绿。**三组缺一不可**：
#   少了第三组就只证明了"它会红"，证明不了"它不是永远红"。
#
# ⚠️ 三条纪律（本会话踩出来的）：
#  ① 破坏**只动本会话新建的临时文件**，绝不改仓库既有文件（多会话并行时不动对方的）。
#  ② 每个违规用完立刻删，脚本结尾 `trap` 兜底 + **打印残留数**。
#  ③ 造违规**必须先读判据**（含正则在字符层面认什么），否则是同义反复 ——
#     本会话为此误判过两道门"坏了"。
#
# ⚠️ 已知**不可造**的门（不在本脚本里，如实记在这里）：
#   check-eol-policy —— `.gitattributes` 的 `eol=lf` 在 `git add` 时就把 CRLF 规范化成 LF，
#   违规在 `git add` 那一刻就被消掉 ⇒ **造不出新违规**；它的第二维只能抓"属性存在之前就入库"的历史遗留。
set -uo pipefail
cd "$(dirname "$0")/.."

JAVA=server/game-web/src/main/java
# ⚠️ layering 门扫的是 **PURE_MODULES=(game-common game-core game-battle)**，**不含 game-web**
#    ⇒ 造它的违规必须另放一个临时 java 文件到 game-core，放到 $JAVA 会静默不红。
JAVA_LAYER=server/game-core/src/main/java/ZzGateLayerSelfTest.java
TS=client/assets/scripts
MJS=tools
TMP_JAVA="$JAVA/ZzGateSelfTest.java"
TMP_TS="$TS/zz-gate-selftest.ts"
TMP_MJS="$MJS/zz-gate-selftest.mjs"

cleanup() {
  rm -f "$TMP_JAVA" "$TMP_TS" "$TMP_TS.meta" "$TMP_MJS" "$JAVA_LAYER"
  rm -rf /d/tmp/probe753/gates-defs /d/tmp/probe753/gates-ep
}
trap cleanup EXIT

pass=0; fail=0
note() { if [ "$1" = "ok" ]; then pass=$((pass + 1)); else fail=$((fail + 1)); fi; }

# 每道门的默认执行方式 = `bash scripts/<门>`；three_arg 用 GATE_RUN/GATE_MAKE 覆盖。
# ⚠️ 必须先给空值：脚本是 `set -u`，`${GATE_RUN:-}` 在**未定义**时虽然安全，
#    但 `local run="${GATE_RUN:-}"` 之后再 `$run` 为空时走 fallback 才是对的 —— 这里显式声明避免歧义。
GATE_RUN=""
GATE_MAKE=""

# three: $1=门 $2=造违规的函数名 $3=该门的说明
#   可先用 GATE_RUN 覆盖执行方式（默认 `bash scripts/<门>`）；GATE_MAKE 覆盖造违规函数。
three() {
  local gate="$1" make="${GATE_MAKE:-$2}" desc="$3"
  local run="${GATE_RUN:-}"
  printf '\n--- %s（%s）---\n' "$gate" "$desc"
  # ⚠️⚠️ **不能用 `[ -n "$run" ] && "$run" … || bash …`**：
  #    违规时 `$run` 退 1 ⇒ `||` 分支会**接着跑默认命令**（它退 0）⇒ **退出码被覆盖成 0**
  #    ⇒ 读数永远是"违规时=0"。这与本会话早先记的「`node --check` 挂在 `&&` 链后面被短路掩盖」同源。
  #    ⇒ 必须用 if/else，**让退出码原样传出**。
  if [ -n "$run" ]; then
    "$make" 0; "$run" >/dev/null 2>&1; local base=$?
    "$make" 1; "$run" >/dev/null 2>&1; local broken=$?
    "$make" 0; "$run" >/dev/null 2>&1; local restored=$?
  else
    "$make" 0; bash "scripts/$gate" >/dev/null 2>&1; local base=$?
    "$make" 1; bash "scripts/$gate" >/dev/null 2>&1; local broken=$?
    "$make" 0; bash "scripts/$gate" >/dev/null 2>&1; local restored=$?
  fi
  printf '  基线=%s 还原后=%s 违规时=%s\n' "$base" "$restored" "$broken"
  if [ "$base" = "0" ] && [ "$restored" = "0" ] && [ "$broken" != "0" ]; then
    note ok; printf '  ✔ 三读数齐全（绿/绿/红）\n'
  else
    note bad; printf '  ✘ 三读数不齐 —— 判据未确证（基线或还原不绿，或造了违规也不红）\n'
  fi
}

mk_sched() { # check-no-scheduled：生产源码里不出现 @Scheduled
  if [ "$1" = "1" ]; then
    printf 'import org.springframework.scheduling.annotation.Scheduled;\npublic class ZzGateSelfTest { @Scheduled void f() {} }\n' > "$TMP_JAVA"
  else rm -f "$TMP_JAVA"; fi
}
mk_handout() { # check-no-handout：FORBIDDEN_REGEX = Weak/Underdog/… × Bonus/Buff/…
  if [ "$1" = "1" ]; then
    printf 'public class ZzGateSelfTest { static final String N = "weakBonus"; }\n' > "$TMP_JAVA"
  else rm -f "$TMP_JAVA"; fi
}
mk_payment() { # check-no-payment-bypass：源码里出现米大师之外的支付入口
  if [ "$1" = "1" ]; then
    printf "export const zzGateSelfTest = 'alipay'\n" > "$TMP_TS"
  else rm -f "$TMP_TS" "$TMP_TS.meta"; fi
}
mk_reddot() { # check-no-scattered-reddot：散落的红点开关
  if [ "$1" = "1" ]; then
    printf "export const zzGateSelfTest = 'showRedDot'\n" > "$TMP_TS"
  else rm -f "$TMP_TS" "$TMP_TS.meta"; fi
}
mk_perm() { # check-permission-bits：requirePermission 的实参必须在 role_permission 表里
  # ⚠️ 门里的正则是 /"([A-Z_]+)"/g ⇒ **只认全大写**，写小写它匹配不到（会误判成"门坏了"）。
  if [ "$1" = "1" ]; then
    printf 'public class ZzGateSelfTest { void f() { requirePermission("scope", "ZZ_GATE_SELFTEST_NOT_IN_TABLE"); } }\n' > "$TMP_JAVA"
  else rm -f "$TMP_JAVA"; fi
}
mk_ts_meta() { # check-ts-meta：.ts 必须配 .ts.meta
  if [ "$1" = "1" ]; then
    printf 'export const zzGateSelfTest = 1\n' > "$TMP_TS"
  else rm -f "$TMP_TS" "$TMP_TS.meta"; fi
}
mk_dangling() { # check-dangling-test-refs：**只扫 git 已跟踪的文件** ⇒ 必须 git add 才看得见
  # ⚠️⚠️ 必须用 `git add`，**不能用 `git add -N`**（intent-to-add 只是登记路径、不放内容进索引）
  #    —— 本脚本第一版写成 `-N`，被它自己的三读数当场抓红（基线/还原 0、违规也 0）。
  if [ "$1" = "1" ]; then
    cp "$MJS/report-client-send-paths.mjs" "$TMP_MJS"
    # ⚠️ 被指向的类名**必须以 `Test` 结尾** —— 门的正则只认"测试类"形状。
    #    第一版写成 `…NoSuchClass` ⇒ 门不认 ⇒ 违规没被识别（第二版被三读数抓红）。
    printf '\n// 判据见 ZzGateSelfTestNoSuchTest\n' >> "$TMP_MJS"
    git add "$TMP_MJS" >/dev/null 2>&1
  else
    git rm -q --cached "$TMP_MJS" >/dev/null 2>&1
    rm -f "$TMP_MJS"
    git reset -q -- "$TMP_MJS" >/dev/null 2>&1
  fi
}

mk_bot_priv() { # check-no-bot-privilege：源码里不出现 isBot
  if [ "$1" = "1" ]; then
    printf 'public class ZzGateSelfTest { boolean f() { return isBot; } }\n' > "$TMP_JAVA"
  else rm -f "$TMP_JAVA"; fi
}
mk_layering() { # check-layering：领域层禁 import 框架 / 禁 Math.random|System.currentTimeMillis
  # ⚠️ 两段正则分开验：FORBIDDEN_IMPORT_REGEX 只认 **行首 import**（'^[[:space:]]*import …'），
  #    所以 import 行必须顶格、前面不能有别的代码。
  # ⚠️ 文件必须落在 PURE_MODULES 里的 game-core（见上方 JAVA_LAYER 的注释）。
  if [ "$1" = "1" ]; then
    printf 'import org.springframework.stereotype.Component;\npublic class ZzGateLayerSelfTest {}\n' > "$JAVA_LAYER"
  else rm -f "$JAVA_LAYER"; fi
}
mk_mathrandom() { # check-layering 的 FORBIDDEN_CALL_REGEX 分支（只查 FLOAT_MODULES，含 game-core）
  if [ "$1" = "1" ]; then
    printf 'public class ZzGateLayerSelfTest { double f() { return Math.random(); } }\n' > "$JAVA_LAYER"
  else rm -f "$JAVA_LAYER"; fi
}

mk_contract_defs() { # check-contract-defs：同名 def 的结构（去掉 description）必须一致
  # ⚠️ 走**可选目录口**（`node scripts/check-contract-defs.js <dir>`，17:40x 新加，
  #    照 check-config-refs.js 的样式；不传参时行为与改动前完全一致）
  #    ⇒ **零污染**：不碰 contract/proto 里任何入库契约。
  # ⚠️ def 必须写在 **`$defs`** 键下（脚本 L44 是 `doc.$defs || {}`）——
  #    写成 `defs` 会被读成「0 个 def 名」，门绿着但什么都没量到（本格实测踩过）。
  # ⚠️ 它有 `files.length < 15` 的下限 ⇒ 合法样本也要造够 15 份。
  local d=/d/tmp/probe753/gates-defs
  if [ "$1" = "1" ]; then
    rm -rf "$d"; mkdir -p "$d"
    local i
    for i in $(seq 1 15); do
      printf '{"type":"object","$defs":{"ZzCommon":{"type":"integer","description":"%s"}}\n}\n' "$i" > "$d/proto$i.schema.json"
    done
    # 让两份同名 def 结构不同（description 允许不同，其余不能不同）
    printf '{"type":"object","$defs":{"ZzCommon":{"type":"integer","description":"a"}}\n}\n' > "$d/proto16.schema.json"
    printf '{"type":"object","$defs":{"ZzCommon":{"type":"string","description":"b"}}\n}\n' > "$d/proto17.schema.json"
  fi
  GATE_ARGS_DEF=()
  if [ "$1" = "1" ]; then GATE_ARGS_DEF=("$d"); else rm -rf "$d"; GATE_ARGS_DEF=(); fi
}

three_arg() { # 带自定义执行方式的门：$1=门 $2=造违规函数 $3=执行函数 $4=说明
  GATE_MAKE="$2"; GATE_RUN="$3"
  three "$1" "$2" "$4"
  GATE_MAKE=""; GATE_RUN=""
}
GATE_ARGS_DEF=()
run_contract_defs() { node scripts/check-contract-defs.js "${GATE_ARGS_DEF[@]}"; }
three_arg check-contract-defs.sh \
          mk_contract_defs run_contract_defs '同名 def 结构必须一致（走可选目录口，零污染）'

mk_endpoint_paths() { # check-endpoint-paths：客户端绑的每个路径服务端必须真的有
  # ⚠️ 走**可选参数口**（`node scripts/check-endpoint-paths.js <java根> <GameApi文件>`，17:41x 新加）
  #    ⇒ **零污染**：不碰仓库里的控制器与客户端 API。
  local d=/d/tmp/probe753/gates-ep
  if [ "$1" = "1" ]; then
    rm -rf "$d"; mkdir -p "$d/java" "$d/ts"
    cat > "$d/java/ZzGateProbeController.java" <<'EOF'
package com.ironoath.probe;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
@RestController
@RequestMapping("/zz/gateprobe")
public class ZzGateProbeController {
    @GetMapping("/ok") public String ok() { return "ok"; }
}
EOF
    printf "const A = '/zz/gateprobe/ok'\nconst B = '/zz/gateprobe/ghost'\n" > "$d/ts/api.ts"
  else rm -rf "$d"; fi
  EP_ARGS=()
  if [ "$1" = "1" ]; then EP_ARGS=("$d/java" "$d/ts/api.ts"); else EP_ARGS=(); fi
}
run_endpoint_paths() { node scripts/check-endpoint-paths.js "${EP_ARGS[@]}"; }
three_arg check-endpoint-paths.sh \
          mk_endpoint_paths run_endpoint_paths '客户端绑的路径服务端必须存在（走可选参数口，零污染）'

three check-no-scheduled.sh   mk_sched    '禁 @Scheduled'
three check-no-handout.sh     mk_handout  '禁弱势补偿类命名'
three check-no-payment-bypass.sh mk_payment '禁米大师之外的支付入口'
three check-no-scattered-reddot.sh mk_reddot '禁散落红点开关'
three check-permission-bits.sh  mk_perm    '权限位必须在 role_permission 表里（全大写字面量）'
three check-ts-meta.sh         mk_ts_meta  '.ts 必须配 .ts.meta'
three check-dangling-test-refs.sh mk_dangling '悬空测试引用（必须先 git add 才被扫到）'
three check-no-bot-privilege.sh  mk_bot_priv '禁 isBot 特判'
three check-layering.sh          mk_layering '领域层禁 import 框架（FORBIDDEN_IMPORT_REGEX 只认行首 import）'
three check-layering.sh          mk_mathrandom '领域层禁 Math.random（FORBIDDEN_CALL_REGEX 分支）'

cleanup
printf '\n=== 结果：合格 %d 条 / 不合格 %d 条 ===\n' "$pass" "$fail"
printf '残留：java=%s layer=%s ts=%s ts.meta=%s mjs=%s\n' \
  "$(ls "$TMP_JAVA" 2>/dev/null | wc -l)" "$(ls "$JAVA_LAYER" 2>/dev/null | wc -l)" \
  "$(ls "$TMP_TS" 2>/dev/null | wc -l)" \
  "$(ls "$TMP_TS.meta" 2>/dev/null | wc -l)" "$(ls "$TMP_MJS" 2>/dev/null | wc -l)"
if [ "$fail" -gt 0 ]; then
  echo "[check-gates-can-fail] 失败：有门禁的『三读数』不齐 —— 它们可能只是在『没触发』。" >&2
  exit 1
fi
echo "[check-gates-can-fail] 通过：以上门禁都验过基线绿 / 还原绿 / 违规红。"