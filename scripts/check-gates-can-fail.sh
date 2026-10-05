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
# ⚠️ 各 mk_* 的临时目录：必须在 cleanup 定义**之前**声明（脚本是 set -u）。
TC_D=/d/tmp/probe753/gates-tcov
PS_D=/d/tmp/probe753/gates-pkgsz
DC_D=/d/tmp/probe753/gates-doccnt
PP_D=/d/tmp/probe753/gates-pkgpath
SP_D=/d/tmp/probe753/gates-sendpaths
CW_D=/d/tmp/probe753/gates-corewire

cleanup() {
  rm -f "$TMP_JAVA" "$TMP_TS" "$TMP_TS.meta" "$TMP_MJS" "$JAVA_LAYER"
  rm -rf /d/tmp/probe753/gates-defs /d/tmp/probe753/gates-ep "$TC_D" "$PS_D" "$DC_D" "$PP_D" "$SP_D" "$CW_D"
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

TC_D=/d/tmp/probe753/gates-tcov
mk_track_coverage() { # check-track-coverage：AppRoot 的每个面板动作都必须打点
  # ⚠️ 走**可选参数口**（`bash scripts/check-track-coverage.sh <AppRoot.ts> <TrackEvents.ts>`，23:2x 新加）
  #    ⇒ **零污染**：不碰仓库里的客户端源码。
  # ⚠️ `three` 的约定是 **`$1=1` 表示"植入违规"** ⇒ 这里直接造**不打点**的版本。
  # ⚠️ 判据两处都是**字面量匹配**，造样必须照抄：
  #    decl = /^ {2}(?:async )?(?!private |get |set |constructor)([a-z][A-Za-z0-9]*)\(/ 且行尾以 { 收尾
  #    打点 = 方法体里出现字面量 **.track(** ⇒ 写 track.xxx() **不算**（那是 "track."）
  TC_ARGS=()
  if [ "$1" = "1" ]; then
    rm -rf "$TC_D"; mkdir -p "$TC_D"
    # 违规态：方法体里**没有** .track(
    printf 'export class AppRoot {\n  zzGateProbeClick(): void {\n    this.send()\n  }\n}\n' > "$TC_D/AppRoot.ts"
    printf "export const TrackEvents = {\n  zzGateProbeClick: 'zz_gate_probe_click',\n}\n" > "$TC_D/TrackEvents.ts"
    TC_ARGS=("$TC_D/AppRoot.ts" "$TC_D/TrackEvents.ts")
  else
    rm -rf "$TC_D"
  fi
}
run_track_coverage() { bash scripts/check-track-coverage.sh "${TC_ARGS[@]}"; }
three_arg check-track-coverage.sh \
          mk_track_coverage run_track_coverage 'AppRoot 每个面板动作都必须打点（走可选参数口，零污染）'

mk_package_size() { # check-package-size：首包 ≤ PERF_FIRST_PACKAGE_MAX_BYTES
  # ⚠️ 覆盖口用**环境变量**（PKGSIZE_GLOBAL_JSON / PKGSIZE_BUILD_DIR / PKGSIZE_SOURCE_DIR，23:4x 新加）
  #    ⇒ 不设这三个时逐字节等同改动前；设了就换成临时表与临时产物 ⇒ **零污染**。
  if [ "$1" = "1" ]; then
    rm -rf "$PS_D"; mkdir -p "$PS_D/build" "$PS_D/src"
    # 只含门会查的两个 id；预算压到 1000 字节，产物造 5KB ⇒ 必然超
    printf '{"rows":[{"id":"PERF_FIRST_PACKAGE_MAX_BYTES","value":1000},{"id":"PERF_TOTAL_PACKAGE_MAX_BYTES","value":100000}]}\n' > "$PS_D/global.json"
    head -c 5000 /dev/zero | tr '\0' 'x' > "$PS_D/build/blob.bin"
    printf 'aaaa' > "$PS_D/src/tiny.ts"
    export PKGSIZE_GLOBAL_JSON="$PS_D/global.json" PKGSIZE_BUILD_DIR="$PS_D/build" PKGSIZE_SOURCE_DIR="$PS_D/src"
  else
    unset PKGSIZE_GLOBAL_JSON PKGSIZE_BUILD_DIR PKGSIZE_SOURCE_DIR
    rm -rf "$PS_D"
  fi
}
run_package_size() { bash scripts/check-package-size.sh; }
three_arg check-package-size.sh \
          mk_package_size run_package_size '首包不得超过 PERF_FIRST_PACKAGE_MAX_BYTES（走环境变量口，零污染）'

mk_doc_counts() { # check-doc-counts：AGENTS.md 声明的门禁道数必须等于 check.sh 实际调用数
  # ⚠️ 走**环境变量口**（DOCCOUNT_CHECK_SH / DOCCOUNT_AGENTS_MD，2026-10-06 新加）⇒ **零污染**：
  #    不碰仓库里的 AGENTS.md 与 scripts/check.sh（后者正被别的会话改，动它就是搅在一起）。
  if [ "$1" = "1" ]; then
    rm -rf "$DC_D"; mkdir -p "$DC_D"
    # 违规态：脚本里 4 道，文档写 3 道
    printf 'bash scripts/check-a.sh\nbash scripts/check-b.sh\nbash scripts/check-c.sh\nbash scripts/check-d.sh\n' > "$DC_D/check.sh"
    printf '| 静态门（3 道）+ 客户端单测 | x |\n' > "$DC_D/AGENTS.md"
    export DOCCOUNT_CHECK_SH="$DC_D/check.sh" DOCCOUNT_AGENTS_MD="$DC_D/AGENTS.md"
  else
    unset DOCCOUNT_CHECK_SH DOCCOUNT_AGENTS_MD
    rm -rf "$DC_D"
  fi
}
run_doc_counts() { bash scripts/check-doc-counts.sh; }
three_arg check-doc-counts.sh \
          mk_doc_counts run_doc_counts '文档声明的门禁道数必须等于实际调用数（走环境变量口，零污染）'

mk_pkg_path_predicates() { # check-package-path-predicates：量具里写死的包路径谓词必须命中真实文件/目录
  # ⚠️ 走**环境变量口**（PKGPATH_SCAN_DIRS / PKGPATH_SERVER_DIR，2026-10-06 新加）⇒ **零污染**：
  #    不碰仓库里的 17 条门禁字面量路径，也不动 Java 源码树。
  # ⚠️ 违规夹具里**必须同时放一条合法的 exec.mainClass**：本门有「每族谓词命中数 > 0」的下限，
  #    少了它会让判红变成"两条都不成立"，红点归因不到①（本仓做法：植入要逐条干净 FAIL）。
  if [ "$1" = "1" ]; then
    rm -rf "$PP_D"; mkdir -p "$PP_D"
    # ⚠️ 幽灵路径必须**分两段拼出来**：本仓库自身的 `scripts/` 就在被扫范围内，
    #    一条完整的假路径写进本文件 ⇒ 基线自己就红（本轮被三读数的第一读数当场抓到）。
    printf 'const ghost = "%s/%s"\n' \
      'server/game-web/src/main/java/com/ironoath' 'zzgateprobe/NoSuch.java' > "$PP_D/a.js"
    printf 'mvn -Dexec.mainClass=com.ironoath.codegen.ContractGenMain\n' >> "$PP_D/a.js"
    export PKGPATH_SCAN_DIRS="$PP_D"
  else
    unset PKGPATH_SCAN_DIRS
    rm -rf "$PP_D"
  fi
}
run_pkg_path_predicates() { bash scripts/check-package-path-predicates.sh; }
three_arg check-package-path-predicates.sh \
          mk_pkg_path_predicates run_pkg_path_predicates '量具写死的包路径谓词必须命中真实路径（走环境变量口，零污染）'

mk_client_send_paths() { # check-client-send-paths：GameApi 的每个发送口都必须有生产调用点
  # ⚠️ 走**四个环境变量口**（SENDPATHS_CLIENT / TESTS / TOOLS / API，2026-10-06 加在报告器上）
  #    ⇒ **零污染**：不往客户端源码里植死方法，也不读真 GameApi。
  # ⚠️ 违规态 = 夹具里 `beta()` **没有任何生产调用点**；`alpha()` 必须被 prod.ts 调到，
  #    否则两条方法都成缺口，红点归因不到"新增没人调的发送口"这一形状。
  if [ "$1" = "1" ]; then
    rm -rf "$SP_D"; mkdir -p "$SP_D/client" "$SP_D/tests" "$SP_D/tools"
    printf 'export class GameApi {\n  alpha(): void {}\n  beta(): void {}\n}\n' > "$SP_D/client/GameApi.ts"
    printf 'export function go(api: any) { return api.alpha() }\n' > "$SP_D/client/prod.ts"
    export SENDPATHS_CLIENT="$SP_D/client" SENDPATHS_TESTS="$SP_D/tests" \
           SENDPATHS_TOOLS="$SP_D/tools" SENDPATHS_API="$SP_D/client/GameApi.ts"
  else
    unset SENDPATHS_CLIENT SENDPATHS_TESTS SENDPATHS_TOOLS SENDPATHS_API
    rm -rf "$SP_D"
  fi
}
run_client_send_paths() { bash scripts/check-client-send-paths.sh; }
three_arg check-client-send-paths.sh \
          mk_client_send_paths run_client_send_paths 'GameApi 每个发送口都必须有人调（走环境变量口，零污染）'

mk_core_wiring() { # check-core-wiring：core 的类必须被外层主源码真的引用
  # ⚠️ 走**两个环境变量口**（COREWIRING_CORE_DIR / COREWIRING_CONSUMER_DIR，2026-10-06 新加）⇒ **零污染**：
  #    不碰 server/ 那 111 个类与 797 个外层文件，只造一棵假树。
  # ⚠️ 违规态 = 假树里 `ZzOrphan.java` 没有任何外层引用，而 `ZzWired.java` **有**（Bar.java 引用它）：
  #    两条一起放才证明门不是"恒红"也不是"恒绿"——只放孤儿会让"门永远判红"这个反例排除不掉。
  if [ "$1" = "1" ]; then
    # ⚠️ 目录里的 `java` 那一段必须**用变量拼**：本文件在 scripts/ 里，而 check-package-path-predicates.sh
    #    会抽走任何形如 `…/src/main/java/com/…` 的字面量去查存在性 —— 临时树的路径归一不到仓库相对路径，
    #    于是基线自己就红（本轮第二次踩同族，第一次是假路径字面量）。
    local J=java
    rm -rf "$CW_D"; mkdir -p "$CW_D/core/com/zz" "$CW_D/web/src/main/$J/com/zz"
    printf 'package com.zz;\npublic class ZzOrphan { public int unused() { return 1; } }\n' > "$CW_D/core/com/zz/ZzOrphan.java"
    printf 'package com.zz;\npublic class ZzWired { public int used() { return 2; } }\n' > "$CW_D/core/com/zz/ZzWired.java"
    printf 'package com.zz;\npublic class Bar { public int b() { return new ZzWired().used(); } }\n' > "$CW_D/web/src/main/$J/com/zz/Bar.java"
    export COREWIRING_CORE_DIR="$CW_D/core" COREWIRING_CONSUMER_DIR="$CW_D"
  else
    unset COREWIRING_CORE_DIR COREWIRING_CONSUMER_DIR
    rm -rf "$CW_D"
  fi
}
run_core_wiring() { bash scripts/check-core-wiring.sh; }
three_arg check-core-wiring.sh \
          mk_core_wiring run_core_wiring 'core 的类必须被外层主源码引用（走环境变量口，零污染）'

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