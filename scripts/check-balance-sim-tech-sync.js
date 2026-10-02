#!/usr/bin/env node
// 职责：盯住 `balance-sim` 的科技加成**必须从 tech 表读**，且 `--tech-level` 这条参数面
// 不会被悄悄改掉（收口清单 #641 / #643）。
//
// 为什么需要这条门：`BalanceCli` 自己重算产出（`base × BUILDING_OUTPUT^(level-1)`），
// 科技乘数是本轮新接进去的。**这类「口径」最危险的失效方式是某天有人为了让读数好看
// 直接写死一个百分比** —— 那时模拟器照常输出一整套读数，却全是错的（与 #592 capBase、
// #594 initCap、#596 完全同族：那三个都是「两边漂移、模拟器照常报数」）。
//
// 本门能失败：把 TechBonusCore.totalPercent 的调用换成写死常量，删掉 `--tech-level`
// 解析，或者把倍率写成 `* techLevel`（而不是读表），本门都会红。
//
// 这个 wrapper 保持纯 ASCII：非 ASCII 注释会让 bash 误解码并让 check.sh 以 127 中断
// （见 check-balance-sim-config-sync.sh 的先例）。
//
// ⚠️ 模块形式必须跟既有门一致 —— 本仓 package.json **没有** `type: module`，所以 `.js` 按 CJS 跑。
// 第一版这里写的是 ESM 的 `import`，于是门**根本没执行**就抛 `SyntaxError` 退出 1，
// 三次红全��同一个错 —— 而那看起来像「门能失败」，其实是「门从来没跑过」。
// 判据本身也要有判据：第一次跑完必须确认它在**无违规**时能报绿。
const { readFileSync } = require('fs')

const cliPath = 'server/tools/balance-sim/src/main/java/com/ironoath/battle/sim/BalanceCli.java'
const src = readFileSync(cliPath, 'utf8')
const problems = []

const mustHave = [
  ['reads --tech-level from options', /getOrDefault\(\s*"tech-level"\s*,\s*"0"\s*\)/],
  ['reuses TechBonusCore (not a private copy)', /com\.ironoath\.common\.config\.TechBonusCore\.totalPercent/],
  ['rows come from TechCfg, not hardcoded', /configs\.all\(\s*com\.ironoath\.config\.cfg\.TechCfg\.class\s*\)/],
  ['multiplier uses the 10000 fixed-point scale', /10_000L\s*\+/],
  ['every one of the four *_OUTPUT attrs is asked for', new RegExp([
    'WOOD_OUTPUT', 'STONE_OUTPUT', 'IRON_OUTPUT', 'GRAIN_OUTPUT',
  ].map((a) => `EffectAttr\\.${a}\\.name\\(\\)`).join('[\\s\\S]{0,400}?'), 'g')],
]

// the four-attr check is a count, not a single match: count distinct attr usages
const attrCount = ['WOOD_OUTPUT', 'STONE_OUTPUT', 'IRON_OUTPUT', 'GRAIN_OUTPUT']
  .filter((a) => src.includes(`EffectAttr.${a}.name()`)).length
if (attrCount !== 4) {
  problems.push(`只问了 ${attrCount}/4 个 *_OUTPUT 属性（缺的就是那一项加成被静默丢掉）`)
}

for (const [name, re] of mustHave) {
  if (name.startsWith('every one of')) continue
  if (!re.test(src)) problems.push(`缺：${name}`)
}

// The report must keep saying this is an upper bound, not a player profile.
// ⚠️ 必须匹配**报告里真正打印的那一行**（`System.out.println`），不能只查文件里有没有「上界」三个字
// —— 源码注释里也写着「上界」（L500/L502），第一版就栽在这：把注释那处改掉，门照样报绿。
// 这就是「判据要能失败才算数」的第三种翻法：判据成立、但它盯的不是你以为的那一处。
const printedLines = src
  .split('\n')
  .filter((l) => l.includes('System.out.print'))
  .join('\n')
if (!/上界/.test(printedLines)) {
  problems.push('报告输出里没有「上界」字样 —— 这个读数一旦被当成玩家画像就会误导验收')
}

if (problems.length > 0) {
  console.error('[check-balance-sim-tech-sync][FAIL] 科技加成的口径脱钩了：')
  for (const p of problems) console.error(`  - ${p}`)
  console.error('  修法：倍率必须由 TechBonusCore 从 tech 表算出（Σ effectValue x 等级），')
  console.error('  不许在 BalanceCli 里写死任何百分比。先例与口径见收口清单 #636 / #641。')
  process.exit(1)
}

console.log('[check-balance-sim-tech-sync] 科技加成四属性全部走 TechBonusCore x tech 表，'
  + '倍率用 10000 定点刻度，报告标注「上界」。')
