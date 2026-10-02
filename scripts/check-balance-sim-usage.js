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

  // ---- #697: usage must not hard-code values that live in config tables. #679 changed
  // `warehouse.capBase` 8000 -> 32000 and the `--cap-base` help line kept saying「现值 8000」;
  // `--troops` advertised「兵力上限（默认 6833）」while the actual training cap is
  // `population x TROOP_PER_COMMAND` (= 230). Both are「文档说 A、代码是 B」shapes, and both
  // shipped because nothing read the usage text against the tables. This gate fails when a
  // help line prints a number that is *also* a current config-table value, unless the same line
  // explicitly says the number is read from a table at runtime.
  const cfgFiles = [
    'contract/config/building.json',
    'contract/config/curve.json',
    'contract/config/resource.json',
  ]
  const tableValues = new Set()
  for (const f of cfgFiles) {
    const txt = readFileSync(f, 'utf8')
    for (const m of txt.matchAll(/:\s*(\d{2,})/g)) tableValues.add(m[1])
  }
  const hardcoded = []
  for (const l of usage.split('\n')) {
    if (!/--[a-z-]+=/.test(l)) continue
    // ⚠️ **本门第一版有个漏洞，已修**（#697 自测）：原来「同一行声明了从表读就放行」，
    // 而「默认**读** building 表 capBase，**现值 32000**」这种句子**同时**满足两条件
    // ⇒ 正好是本门要禁的形状却放行了。注入测试当场抓出这一点。
    // ⇒ 现在规则收紧为：**usage 里只要出现「现值 N」/「默认 N」且 N 是表里现值，一律红**，
    // 不给「顺带声明了读表」留口子。要放行就只能不写具体数值（写「实时读表的那个值」）。
    for (const m of l.matchAll(/(?:现值|默认)\s*(\d{2,})/g)) {
      if (tableValues.has(m[1])) hardcoded.push({ line: l.trim(), value: m[1] })
    }
  }
  if (hardcoded.length > 0) {
    console.error('[check-balance-sim-usage][FAIL] usage 里写死了配置表当前的数值：')
    for (const h of hardcoded) console.error(`  - 「${h.line}」里的 ${h.value} 也是配置表里的现值`)
    console.error('  改表不改 usage 就会变成「文档说 A、代码是 B」（本会话已发生两次：')
    console.error('  #679 改 warehouse.capBase 8000 -> 32000、--troops 的 6833 与实际 230 量纲不同）。')
    console.error('  修法：usage 只写「从哪张表的哪个字段读」，不抄具体数值；')
    console.error('  或者同一行显式写「实时读表」，本门会放行。依据：收口清单 #696/#697。')
    process.exit(1)
  }

const documented = [...switches].filter((k) => usage.includes('--' + k))
console.log(`[check-balance-sim-usage] usage 覆盖全部 ${documented.length}/${switches.size} 个开关，`
  + `f2p7d 族必需项全在。`)
if (skipped.length > 0) {
  console.log(`  有意不列的内部开关：${skipped.map((k) => '--' + k).join('、')}`)
}