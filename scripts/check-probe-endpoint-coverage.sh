#!/usr/bin/env bash
# 「客户端绑定的每一条路径都得有人驱动」的上限门（#2519）。
#
# 为什么要有它：`#2518` 现跑数出 147 条绑定路径里有 33 条没有任何探针驱动。
# 这类缺口只会单向变多 —— 新加一个客户端发送口不需要任何人写探针，而
# `check-endpoint-paths.sh` 只保证「发送口有生产调用点」，不保证「有人验过它」。
# 门不要求把 33 条补齐（那是工作量，不是纪律），只要求**不许变多**。
#
# 判据（四层，谓词失效一律判红而不是判绿）：
#   1. 绑定路径清单读得到，且条数不低于下限（默认 100）——真源坏了不许当"没东西要测"；
#   2. 探针目录里有 .mjs，且份数不低于下限（默认 50）；
#   3. 无驱动条数 <= 上限（默认 31：2026-11-13 #2520 补齐 /player/power 与 /city/harvest-learning（33→31）、
#      #2521 让 verify-gacha-history.mjs 在源码里点出 /gacha/history 并断言请求真的发生（31→30）、
#      #2522 把「注释也算驱动」这个假阳性修掉后**诚实地回到 31**（/nation/relations 只因一句注释被算成有驱动）；
#      可被 PROBE_COVERAGE_MAX_UNDRIVEN 覆盖）；
#   4. 超上限时逐条打印新增的无驱动路径（要修的那一份就是从这几行里挑）。
#
# 约定（#2521 踩出来的）：本门只认探针**源码里的路径字面量**。用 UI 点击驱动接口的探针
# （读场景图、不断言网络）必须**同时**在源码里出现那条路径并把请求捞下来断言一次 ——
# 否则要么量具看不见它（被算成「没人驱动」），要么屏上文字是本地拼的也能绿。
#
# 输入口（自测用，零污染）：PROBE_COVERAGE_PATHS_JSON / PROBE_COVERAGE_TOOLS_DIR /
# PROBE_COVERAGE_MAX_UNDRIVEN / PROBE_COVERAGE_MIN_PATHS / PROBE_COVERAGE_MIN_PROBES。
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v node >/dev/null 2>&1; then
  echo "[probe-endpoint-coverage][FAIL] 找不到 node，无法判据（不当作通过）"
  exit 1
fi

MAX_UNDRIVEN="${PROBE_COVERAGE_MAX_UNDRIVEN:-31}"
MIN_PATHS="${PROBE_COVERAGE_MIN_PATHS:-100}"
MIN_PROBES="${PROBE_COVERAGE_MIN_PROBES:-50}"

rc=0
probe_output="$(node - "$MAX_UNDRIVEN" "$MIN_PATHS" "$MIN_PROBES" <<'NODE'
const fs = require('fs');
const path = require('path');
const root = process.cwd();
const maxUndriven = Number(process.argv[2]);
const minPaths = Number(process.argv[3]);
const minProbes = Number(process.argv[4]);

const need = (rel) => require(path.join(root, rel));
let bound;
try {
  bound = need('scripts/lib/endpoint-paths.js').clientBoundPaths();
} catch (error) {
  console.log(`FAIL|绑定路径真源读不到：${error.message}`);
  process.exit(0);
}
if (!Array.isArray(bound) || bound.length < minPaths) {
  console.log(`FAIL|绑定路径条数 ${Array.isArray(bound) ? bound.length : 'N/A'} 低于下限 ${minPaths}：谓词失效，不当作通过`);
  process.exit(0);
}

const toolsDir = process.env.PROBE_COVERAGE_TOOLS_DIR || path.join(root, 'tools');
let classified;
try {
  const pathsFile = process.env.PROBE_COVERAGE_PATHS_JSON;
  const input = pathsFile ? JSON.parse(fs.readFileSync(pathsFile, 'utf8')) : bound;
  classified = need('scripts/lib/endpoint-probe-coverage.js').classify(input, toolsDir);
} catch (error) {
  console.log(`FAIL|分类失败：${error.message}`);
  process.exit(0);
}

if (classified.probes < minProbes) {
  console.log(`FAIL|探针文件只数到 ${classified.probes} 份，低于下限 ${minProbes}：目录搬走或改名都会走到这里`);
  process.exit(0);
}

const lines = [`STAT|${bound.length}|${classified.probes}|${classified.driven.length}|${classified.undriven.length}|${maxUndriven}`];
for (const routePath of classified.undriven) lines.push(`UNDRIVEN|${routePath}`);
console.log(lines.join('\n'));
NODE
)"

fail() {
  echo "[probe-endpoint-coverage][FAIL] $1"
  rc=1
}

stat_line="$(printf '%s\n' "$probe_output" | grep '^STAT|' || true)"
fail_line="$(printf '%s\n' "$probe_output" | grep '^FAIL|' || true)"
if [ -n "$fail_line" ]; then
  fail "${fail_line#FAIL|}"
  exit "$rc"
fi
if [ -z "$stat_line" ]; then
  fail "谓词没有产出 STAT 行（分类器被改坏或 node 提前退出）：不当作通过"
  exit "$rc"
fi

IFS='|' read -r _ paths probes driven undriven max <<<"$stat_line"
echo "[probe-endpoint-coverage] 绑定路径 ${paths} 条；探针 ${probes} 份；有驱动 ${driven} 条；无驱动 ${undriven} 条（上限 ${max}）"

if [ "$undriven" -gt "$max" ]; then
  fail "无探针驱动的绑定路径 ${undriven} 条，超过上限 ${max} 条 —— 新加的客户端发送口请同批给一份探针驱动（或在台账里裁掉它）。超出的这些："
  printf '%s\n' "$probe_output" | grep '^UNDRIVEN|' | sed 's/^UNDRIVEN|/  - /' >&2 || true
  exit "$rc"
fi

echo "[probe-endpoint-coverage] ✓ 无驱动的绑定路径 ${undriven} 条，未超过上限 ${max} 条；新加发送口必须带探针，否则这条会红"
exit "$rc"
