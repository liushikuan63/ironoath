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

// ---- #644: the 武将 line. The bug was a **unit mix-up**: `--population` is a 统帅值
// (command value), but the code used it directly as the 带兵上限, dropping the
// `× TROOP_PER_COMMAND` factor (46 became 46 instead of 230 — a 5x undercount).
// The fix routes it through `HeroCalculator.troopCap`, so the gate must insist on that:
// a hand-rolled `commandValue * 5` in this file is the same defect wearing a different hat.
const heroMustHave = [
  ['reads --population as the command value', /getOrDefault\(\s*"population"\s*,\s*"46"\s*\)/],
  ['uses HeroCalculator.troopCap (not a hand-rolled multiply)', /HeroCalculator\.troopCap\(/],
  ['builds HeroRules from the config table', /new\s+com\.ironoath\.core\.hero\.HeroRules\(/],
  ['TROOP_PER_COMMAND comes from the table', /longParam\(\s*"TROOP_PER_COMMAND"\s*\)/],
]
// ⚠️ 判据只能看**代码**、不能看注释：源码注释里就写着「真值要过 `HeroCalculator.troopCap`」，
// 而把实现改回手写之后正则照样匹配到那行注释 ⇒ **注入违规却报绿**（又一次「门没校准就上」）。
// ⇒ 统一剥掉注释再判：同时挡行首注释（行首 //、*、/*）与行尾（// 之后）。
const codeOnly = src
  .split('\n')
  .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
  .map((l) => l.split('//')[0])
  .join('\n')

for (const [name, re] of heroMustHave) {
  if (!re.test(codeOnly)) problems.push(`武将线缺：${name}`)
}

// ⚠️ 四处坑叠在一起才导致「现状也报红」，逐个记下来（这一格调试的主要产出）：
//   ① 只取含「累计造兵」的**那一行**，而 printf 被拆成续行、真正的「带兵上限」在参数行；
//   ② `findIndex` 命中的是**源码注释**里那句「累计造兵」（L566 那段说明文字）；
//   ③ 只挡**行首**注释（`^\s*//`）⇒ 挡不住**行尾**注释 —— L566 是
//      `boolean pickedOnceThisRound = false;   // #549 均衡升…累计造兵…`，代码在行首、关键词在行尾；
//   ④ 上面那条修完仍红，是因为还没剥掉行尾注释。
// ⇒ 判据：**先剥掉 `//` 及其后内容**（printf 格式串里本仓不含 `//`，无副作用），再找「累计造兵」。
const lines = src.split('\n')
const code = (l) => l.split('//')[0]
const popIdx = lines.findIndex((l) => code(l).includes('累计造兵'))
if (popIdx < 0) {
  problems.push('报告里找不到（非注释的）「累计造兵」打印语句 —— 这一行是造兵读数的唯一出口')
} else {
  const window = lines.slice(popIdx, popIdx + 6).map(code).join('\n')
  if (!/统帅值/.test(window) || !/带兵上限/.test(window)) {
    problems.push('报告的「累计造兵」那行没有分别印出统帅值与带兵上限 —— '
      + '只印一个数字时读者无从判断它是哪个（这正是 #644 口径混用的根）')
  }
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
