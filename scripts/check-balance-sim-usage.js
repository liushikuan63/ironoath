#!/usr/bin/env node
// Duty: every CLI switch BalanceCli actually reads must appear in its own usage text.
//
// Why a gate: `--f2p7d` is the entry point for EVERY "measured N levels / overflow X" reading in
// B02, and its switches (`--tech-level`, `--population`, `--builds-gate`, `--train-unit`, ...)
// were added over many sessions. The usage block listed only 4 modes, so 31 of 35 switches were
// invisible to anyone who ran the tool without reading the source — the exact failure mode where
// a probe knob exists but nobody knows it, so a reading silently uses the default.
//
// This gate can fail: add a `getOrDefault("new-switch", …)` / `containsKey("new-switch")` in
// BalanceCli without documenting it in the usage block, and this turns red.
const { readFileSync } = require('fs')

const cliPath = 'server/tools/balance-sim/src/main/java/com/ironoath/battle/sim/BalanceCli.java'
const src = readFileSync(cliPath, 'utf8')
const lines = src.split('\n')

// The usage block: every line from the one that prints 「用法」 to the one that exits with 2.
const usageStart = lines.findIndex((l) => l.includes('用法（参数一律'))
const usageEnd = lines.findIndex((l, i) => i > usageStart && l.includes('System.exit(2)'))
if (usageStart < 0 || usageEnd < 0) {
  console.error('[check-balance-sim-usage][FAIL] 找不到 usage 块（应有「用法（参数一律」与 System.exit(2)）。')
  process.exit(1)
}
const usage = lines.slice(usageStart, usageEnd + 1).join('\n')

// Collect every switch the CLI actually reads.
const switches = new Set()
for (const re of [/getOrDefault\(\s*"([a-z0-9-]+)"/g, /containsKey\(\s*"([a-z0-9-]+)"/g]) {
  let m
  while ((m = re.exec(src)) !== null) switches.add(m[1])
}

// Switches that are plumbing rather than user-facing knobs. Empty on purpose: every switch the
// CLI reads must be discoverable from the usage text. Adding an entry here is how a knob would
// quietly stop being documented, so there is deliberately no exclusion list to hide behind.
const INTERNAL = new Map()

// (1) Every switch the CLI reads must appear in the usage text -- this is the whole point:
// adding `getOrDefault("new-knob", ...)` without documenting it must turn this red.
const undocumented = [...switches].filter((k) => !usage.includes('--' + k))
const skipped = [...INTERNAL.keys()].filter((k) => switches.has(k) && !usage.includes('--' + k))
const trulyMissing = undocumented.filter((k) => !INTERNAL.has(k))

// (2) The switches a user must be able to discover by name (a subset of (1), kept explicit so the
// f2p7d family -- the entry point for every B02 reading -- can never silently drop one).
const REQUIRED = [
  'f2p7d', 'days', 'cap', 'cap-base', 'base-rate', 'producers', 'priority', 'dims',
  'builds-gate', 'tech-level', 'population', 'train-slots', 'train-unit',
  'out-exponent', 'cost-ratio',
  'matrix', 'rally', 'wall', 'single', 'settle-bench', 'f2p-stages', 'determinism',
  'type', 'troops',
]

const missing = REQUIRED.filter((k) => !usage.includes('--' + k))
if (missing.length > 0) {
  console.error('[check-balance-sim-usage][FAIL] usage 漏了这些必需开关：')
  for (const k of missing) console.error(`  - --${k}`)
  console.error('  修法：每个 getOrDefault/containsKey 读到的开关都要在 usage 块里出现一行，')
  console.error('  带默认值与口径说明。口径见收口清单 #653。')
  process.exit(1)
}
if (trulyMissing.length > 0) {
  console.error('[check-balance-sim-usage][FAIL] 源码读了这些开关，但 usage 里没有：')
  for (const k of trulyMissing) console.error(`  - --${k}`)
  console.error('  这正是「开关存在但没人知道」的形状：探针旋钮已经生效，')
  console.error('  而读数的人跑一遍工具看不到它，只能去翻源码。修法同上。')
  process.exit(1)
}

const documented = [...switches].filter((k) => usage.includes('--' + k))
console.log(`[check-balance-sim-usage] usage 覆盖全部 ${documented.length}/${switches.size} 个开关，`
  + `f2p7d 族必需项全在。`)
if (skipped.length > 0) {
  console.log(`  有意不列的内部开关：${skipped.map((k) => '--' + k).join('、')}`)
}