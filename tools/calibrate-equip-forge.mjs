// 职责：把「强化一级要多少铁」从感觉值变成**量出来的值**（B20 块② §五② 的「base 先量后填」）。
// 用法：node tools/calibrate-equip-forge.mjs [--json]
//
// 判据来自 `B20_科技与装备强化.md` §五② 的裁决原文：
//   「曲线：新增 EQUIP_FORGE_COST（GEOMETRIC，ratio 取 1.22 与 BUILDING_COST 一致；base 由表给）」
//   「上限：equip.json 加行级列 forgeMax（首版 N=10 / SR=15，草值）」
//   「效果：每级 = 基础属性 ×5%（定点）」
// 裁决定了比率与上限，**没有定基数** —— 这个脚本就是补那一步，并且把结论变成会失败的判据
// （公理三：一条只会打印不会失败的量具等于没量）。
//
// 【为什么价格按「属性点」算而不是全局一个数】
// 每级效果 = 该行自己的属性总和 × 5%，所以铁剑（12 点）一级 +0.6，破军刃（45 点）一级 +2.25。
// 若成本不跟着属性走，全服最优解就变成「只强化最高的那一件」—— 一条无脑答案把 16 件装备
// 压成 1 件，其他行沦为摆设。所以一级价 = **属性总和 × K**，K 由本脚本量出。
// 由此得到一条设计不变量：**每 1 铁买到的属性点处处相等**（强化不是套利点）。
//
// 【K 只存一处】K 就是 `curve.EQUIP_FORGE_COST.base`，**不另抄成 equip.json 的行级列**：
// 「属性 × 70」在每一行再存一份，等于同一个数有十六个家，改基忘改行的症状是某些装备贵得离谱
// 而另一些白送 —— 那种错不会报错，只会让玩家先发现。建筑表那族（costBaseWood 逐行给）没有这个
// 问题，因为那些基数本来就互相不同；这里它们全都能由一个系数算出来，那就该只写一次。
//
// 【K 的锚点：一小时铁产量】
// 参考时点取「铁矿产 Lv10」——玩家开始认真穿 SR 装、也第一次觉得铁不够用的那个点。
// 于是 K = Lv10 铁矿每小时产量 ÷ 入门档装备的平均属性总和，再取整到 10 的倍数
// （70 铁/属性点 这种数策划能在会上复述，68.72 不能）。
//
// 【两条自证 = 脚本的退出码】
//   C1（坑不许太浅）：一次强化的铁耗 ≥ 参考时点 **0.5 小时** 的产量，否则点满 10 级只要几小时，
//       「资源有地方花」这条目的没达成；
//   C2（新手够得着）：一件 N 装点满 forgeMax 的总铁耗 ≤ 参考时点 **5 天** 的产量 ——
//       N 档是第一天就能穿满 4 槽的入门装（equip.json designNote），点满不该是无底洞。
// 两条都不写死数字：比率读 curve.BUILDING_COST（= EQUIP_FORGE_COST 应当取的那个值），
// 产量读 building.json 的 iron_mine 与 curve.BUILDING_OUTPUT，上限读 equip.json 的 forgeMax。
// 改了任何一张表，重跑这条命令就知道基数是否还站得住。
import { readFileSync } from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..')
const table = (name) => JSON.parse(readFileSync(path.join(ROOT, 'contract/config', name), 'utf8'))
const num = (v) => (v === null || v === undefined || v === '' ? 0 : Number(v))

const equip = table('equip.json')
const curve = table('curve.json')
const building = table('building.json')

const curveRow = (id) => curve.rows.find((r) => r.id === id)
const ratio = num(curveRow('BUILDING_COST')?.ratio)
if (!(ratio > 1)) {
  // 比率<=1 时几何级数不再增长，"越强化越贵"这条设计前提失效 —— 必须响，而不是算出一个荒谬的 K
  console.error(`[calibrate-equip-forge][FAIL] curve.BUILDING_COST.ratio 必须 > 1，实际=${ratio}`)
  process.exit(1)
}
const outputCurve = curveRow('BUILDING_OUTPUT')
const exponent = num(outputCurve?.exponent)
if (!(exponent > 0)) {
  console.error(`[calibrate-equip-forge][FAIL] curve.BUILDING_OUTPUT.exponent 必须为正，实际=${exponent}`)
  process.exit(1)
}

// 参考时点：铁矿产 Lv10 每小时产多少铁。P(n) = P0 × n^1.08（curve.BUILDING_OUTPUT）
const REFERENCE_MINE_LEVEL = 10
const ironMine = building.rows.find((r) => r.id === 'iron_mine')
if (!ironMine) {
  console.error('[calibrate-equip-forge][FAIL] building.json 里没有 iron_mine 这一行，铁产量无从算起')
  process.exit(1)
}
const ironBasePerHour = num(ironMine.outputBasePerHour)
const ironPerHour = ironBasePerHour * Math.pow(REFERENCE_MINE_LEVEL, exponent)

// 属性总和：might/command/wisdom 三点同价（装备三维在武将算式里是并列的加算项，见 HeroCalculator）
const attrSum = (row) => num(row.might) + num(row.command) + num(row.wisdom)
const rows = equip.rows.map((r) => ({
  id: r.id,
  rarity: r.rarity,
  attrs: attrSum(r),
  forgeMax: num(r.forgeMax),
}))
const missing = rows.filter((r) => !(r.attrs > 0))
if (missing.length > 0) {
  console.error(`[calibrate-equip-forge][FAIL] 这些装备行三维全为 0，强化无从定价：${missing.map((r) => r.id).join(', ')}`)
  process.exit(1)
}
const notAdjudicated = rows.filter((r) => !(r.forgeMax > 0))
if (notAdjudicated.length > 0) {
  console.error(`[calibrate-equip-forge][FAIL] forgeMax 还没进表（${notAdjudicated.length} 行为空）：`
    + '先按 §五② 把 N=10 / SR=15 填进 equip.json，再重跑本脚本')
  process.exit(1)
}

// 入门档（rarity 最低的一批）平均属性 —— 锚点挂在"新手第一件装备"上，而不是挂在 SR 上
const rarities = [...new Set(rows.map((r) => r.rarity))]
const entryRarity = rarities.includes('N') ? 'N' : rarities.sort()[0]
const entryRows = rows.filter((r) => r.rarity === entryRarity)
const entryAvgAttrs = entryRows.reduce((s, r) => s + r.attrs, 0) / entryRows.length

const rawK = ironPerHour / entryAvgAttrs
// 取值规则：取整到 10。1 小时产量 ÷ 平均属性 = 68.7 这种数不进表 —— 表里的数要能被复述
const K = Math.round(rawK / 10) * 10

/** Σ_{n=1..levels} ratio^(n-1)：从 +0 点满到 forgeMax 一共多少个"一级基数"。 */
const sumUnits = (levels) => {
  let s = 0
  for (let n = 1; n <= levels; n++) s += Math.pow(ratio, n - 1)
  return s
}

const priced = rows.map((r) => {
  const firstLevel = r.attrs * K
  const toFull = firstLevel * sumUnits(r.forgeMax)
  return { ...r, costBaseIron: firstLevel, firstLevelHours: firstLevel / ironPerHour, toFull, toFullDays: toFull / (ironPerHour * 24) }
})

const entryCheapest = priced.reduce((a, b) => (b.toFull < a.toFull ? b : a))
const srRows = priced.filter((r) => r.rarity !== entryRarity)
const srDearest = srRows.reduce((a, b) => (b.toFull > a.toFull ? b : a), srRows[0])

const failures = []
// C1：入门档最便宜的一行的单级价也要 ≥ 0.5 小时产量（它是最容易"太便宜"的那个）
const minFirstLevelHours = Math.min(...priced.map((r) => r.firstLevelHours))
if (!(minFirstLevelHours >= 0.5)) {
  failures.push(`C1 不成立：最便宜的一级强化只要 ${(minFirstLevelHours * 60).toFixed(0)} 分钟产量`
    + '（判据 ≥ 30 分钟）—— 强化不是消耗坑，把 K 调高后重跑')
}
// C2：入门档点满整件 ≤ 5 天产量
const maxEntryDays = Math.max(...entryRows.map((r) => priced.find((p) => p.id === r.id).toFullDays))
if (!(maxEntryDays <= 5)) {
  failures.push(`C2 不成立：入门档点满一件要 ${maxEntryDays.toFixed(2)} 天产量`
    + '（判据 ≤ 5 天）—— 新手够不着，把 K 调低后重跑')
}
// C3：取整之后锚点不能跑偏太远（否则"一小时产量"这句话在 why 里就是假的）
if (!(K >= rawK * 0.9 && K <= rawK * 1.1)) {
  failures.push(`取整后的 K=${K} 偏离锚点 ${rawK.toFixed(2)} 超过 10%，why 里"约等于一小时产量"不成立`)
}

const report = {
  referenceMineLevel: REFERENCE_MINE_LEVEL,
  ironPerHour: +ironPerHour.toFixed(1),
  entryRarity,
  entryAvgAttrs: +entryAvgAttrs.toFixed(2),
  rawK: +rawK.toFixed(2),
  costPerAttrPoint: K,
  ratio,
  curveIdIfPresent: curveRow('EQUIP_FORGE_COST') ? 'EQUIP_FORGE_COST' : null,
  entryCheapest: { id: entryCheapest.id, toFullDays: +entryCheapest.toFullDays.toFixed(2) },
  srDearest: srDearest ? { id: srDearest.id, toFullDays: +srDearest.toFullDays.toFixed(2) } : null,
  cheapestFirstLevelHours: +minFirstLevelHours.toFixed(3),
}

console.log(`[calibrate-equip-forge] 参考时点 = iron_mine Lv${REFERENCE_MINE_LEVEL}`
  + `：${ironBasePerHour} × ${REFERENCE_MINE_LEVEL}^${exponent} = ${ironPerHour.toFixed(1)} 铁/小时`)
console.log(`锚点：${entryRarity} 档 ${entryRows.length} 行平均属性 ${entryAvgAttrs.toFixed(2)} 点`
  + ` ⇒ K = ${ironPerHour.toFixed(1)} / ${entryAvgAttrs.toFixed(2)} = ${rawK.toFixed(2)}，取整到 10 ⇒ **每属性点 ${K} 铁/一级**`)
console.log('  行 id                     稀有度  属性  forgeMax    一级价(推导)    点满(铁)   点满(天)')
for (const r of priced) {
  console.log(`  ${r.id.padEnd(24)}${r.rarity.padEnd(7)}${String(r.attrs).padStart(4)}${String(r.forgeMax).padStart(10)}`
    + `${String(r.costBaseIron).padStart(14)}${String(Math.round(r.toFull)).padStart(11)}${r.toFullDays.toFixed(2).padStart(10)}`)
}
console.log(`单价恒定性：每 1 铁买到的属性点 = 0.05 / ${K} = ${(0.05 / K).toFixed(6)}（全部 ${priced.length} 行同一个数）`)
console.log(`最便宜的一级强化 = ${minFirstLevelHours.toFixed(2)} 小时产量；`
  + `入门档点满一件最多 ${maxEntryDays.toFixed(2)} 天；SR 最贵一件 ${srDearest ? `${srDearest.id} = ${srDearest.toFullDays.toFixed(2)} 天` : '（无 SR 行）'}`)
if (process.argv.includes('--json')) console.log(JSON.stringify(report, null, 2))

if (curveRow('EQUIP_FORGE_COST')) {
  // 曲线入表之后，本脚本顺带核对表里的基数与本行推导一致（改了表忘了重跑 ⇒ 这里红）
  const row = curveRow('EQUIP_FORGE_COST')
  const got = num(row.base)
  if (got !== K) {
    failures.push(`curve.EQUIP_FORGE_COST.base = ${got}，但本次量出 ${K} —— 改表后重跑本脚本，`
      + '并把新基数同步进 curve 那一行的 why（行价由「属性总和 × 基数」推导，equip.json 不存价格列）')
  }
  if (num(row.ratio) !== ratio) {
    failures.push(`curve.EQUIP_FORGE_COST.ratio = ${row.ratio} 与 BUILDING_COST 的 ${ratio} 不一致`
      + '（§五② 明写"ratio 取 1.22 与 BUILDING_COST 一致，不另抄一份"）')
  }
  console.log(`[calibrate-equip-forge] curve.EQUIP_FORGE_COST 已在表：base=${got} ratio=${row.ratio}`)
} else {
  console.log('[calibrate-equip-forge] curve.EQUIP_FORGE_COST 尚未入表 —— 本次输出即入表依据')
}

if (failures.length > 0) {
  console.error('[calibrate-equip-forge][FAIL] ' + failures.join('；'))
  process.exit(1)
}
console.log('[calibrate-equip-forge] 三条自证通过：一级 ≥ 30 分钟产量、入门档点满 ≤ 5 天、取整未跑偏锚点。')
