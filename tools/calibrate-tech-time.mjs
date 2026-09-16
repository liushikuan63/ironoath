// 职责：把「研究一级要多少秒」（curve.TECH_TIME.base）从感觉值变成**量出来的值**。
// 用法：node tools/calibrate-tech-time.mjs [--json]
//
// 判据来自 `B20_科技与装备强化.md` §五① 的裁决原文：
//   「11 行全部点满 ≈ 赛季（45 天）的 50%~70%（约 22~32 天）」——
//   科技是一个赛季点不满的坑，但也不至于两个赛季都点不完。这条是**唯一**的外部约束，
//   所以脚本把它写成退出码而不是打印一句建议（公理三：不许"感觉差不多"；
//   一条只会打印不会失败的量具等于没量）。
//
// 为什么全树总时长对基数是**线性**的：`Formula.techTime(base, level, ratio) = base × ratio^(level-1)`，
// 于是"点满全部行" = base × Σ_行 Σ_{k=1..maxLevel} ratio^(k-1)。
// 这个线性关系就是"改比率 ⇒ 整棵树等比缩放，不需要逐行重算"的理由，也是脚本能一步解出 base 的理由。
//
// 数据来源全部现读配置表：赛季天数 = season.json 里 season_01_* 各阶段 durationDays 之和；
// 比率 = curve.TECH_TIME.ratio；等级上限 = tech.json 每行 maxLevel。没有一处写死，
// 所以改了任何一张表，重跑这条命令就得到新的基数 —— 这正是"不预填感觉值"的可执行版本。
import { readFileSync } from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..')
const table = (name) => JSON.parse(readFileSync(path.join(ROOT, 'contract/config', name), 'utf8'))
const num = (v) => (v === null || v === undefined ? 0 : Number(v))

const tech = table('tech.json')
const curve = table('curve.json')
const season = table('season.json')

const techTime = curve.rows.find((r) => r.id === 'TECH_TIME')
if (!techTime) {
  console.error('[calibrate-tech-time][FAIL] curve.json 里没有 TECH_TIME 这一行')
  process.exit(1)
}
const ratio = num(techTime.ratio)
if (!(ratio > 1)) {
  // ratio<=1 时几何级数不再增长，"点不满"这条设计前提直接失效 —— 必须响，而不是算出一个荒谬基数
  console.error(`[calibrate-tech-time][FAIL] TECH_TIME.ratio 必须 > 1，实际=${ratio}`)
  process.exit(1)
}

// 赛季长度：season_01_* 各阶段 durationDays 之和（不写死 45：日切与赛季时长的裁决改表时这里要跟着变）
const seasonDays = season.rows
  .filter((r) => String(r.id).startsWith('season_01'))
  .reduce((sum, r) => sum + num(r.durationDays), 0)
if (!(seasonDays > 0)) {
  console.error('[calibrate-tech-time][FAIL] 从 season.json 里加不出赛季天数（season_01_* 的 durationDays 全空？）')
  process.exit(1)
}
const seasonSeconds = seasonDays * 86400

// 每行 = Σ_{k=1..maxLevel} ratio^(k-1) 个"base 秒"
const rows = tech.rows.map((r) => {
  const maxLevel = num(r.maxLevel)
  let units = 0
  for (let k = 1; k <= maxLevel; k++) units += Math.pow(ratio, k - 1)
  return {
    id: r.id,
    maxLevel,
    requireAcademyLevel: num(r.requireAcademyLevel),
    units,
    topStepUnits: Math.pow(ratio, maxLevel - 1),
  }
})
const totalUnits = rows.reduce((s, r) => s + r.units, 0)
const levels = rows.reduce((s, r) => s + r.maxLevel, 0)

const LOW = 0.5
const HIGH = 0.7
const baseAt = (percent) => (percent * seasonSeconds) / totalUnits
const bandLowBase = baseAt(LOW)
const bandHighBase = baseAt(HIGH)
// 取值规则：区间中点附近取整秒 —— 12.3 秒这种数进表没人记得住，
// 而"基数是几秒"是策划要能在会上复述的东西，所以宁可牺牲区间内的位置也要取整。
const chosen = Math.round(baseAt((LOW + HIGH) / 2))

const totalSeconds = chosen * totalUnits
const fullTreeDays = totalSeconds / 86400
const percent = (totalSeconds / seasonSeconds) * 100
const topRow = rows.reduce((a, b) => (b.topStepUnits > a.topStepUnits ? b : a), rows[0])
const topStepDays = (chosen * topRow.topStepUnits) / 86400
const firstStepMinutes = (chosen * 1) / 60

const report = {
  seasonDays,
  ratio,
  rows: rows.length,
  levels,
  baseSec: chosen,
  bandSec: [+bandLowBase.toFixed(2), +bandHighBase.toFixed(2)],
  totalTreeSeconds: Math.round(totalSeconds),
  fullTreeDays: +fullTreeDays.toFixed(2),
  percentOfSeason: +percent.toFixed(1),
  firstStepMinutes: +firstStepMinutes.toFixed(2),
  topSingleStep: { row: topRow.id, level: topRow.maxLevel, days: +topStepDays.toFixed(2) },
}

console.log(`[calibrate-tech-time] 赛季 = ${seasonDays} 天；比率 = ${ratio}；${rows.length} 行合计 ${levels} 级`)
for (const r of rows) {
  console.log(`  ${r.id} 上限 ${r.maxLevel} 级（学院 ${r.requireAcademyLevel} 级）`
    + `：满级单步 ${((chosen * r.topStepUnits) / 3600).toFixed(1)} 小时`)
}
// §五① 明写"输出必须含'全树总时长'一行" —— 别的行都可以改，这一行不能丢
console.log(`全树总时长 = ${report.totalTreeSeconds} 秒 = ${report.fullTreeDays} 天`
  + ` = 赛季的 ${report.percentOfSeason}%`)
console.log(`基数取值区间（${LOW * 100}%~${HIGH * 100}%）= [${report.bandSec[0]}, ${report.bandSec[1]}] 秒，本次取 ${chosen} 秒（区间内取整）`)
console.log(`第 1 级 = ${chosen} 秒；最慢一步 = ${topRow.id} 第 ${topRow.maxLevel} 级 = ${topStepDays.toFixed(2)} 天`)
if (process.argv.includes('--json')) console.log(JSON.stringify(report, null, 2))

// 两条自证，各自对应一句会写进表里的话：区间内（§五① 那句"50%~70%"）、末级"以天计"。
// 不再加第三条"感觉应该怎样"的判据 —— 裁决只给了区间这一条外部约束，多出来的门槛是发明。
const failures = []
if (!(chosen >= Math.ceil(bandLowBase) && chosen <= Math.floor(bandHighBase))) {
  failures.push(`整数基数 ${chosen} 秒落在区间 [${bandLowBase.toFixed(2)}, ${bandHighBase.toFixed(2)}] 之外`
    + `（全树 ${percent.toFixed(1)}%，不在 ${LOW * 100}%~${HIGH * 100}%）`)
}
if (!(topStepDays >= 1)) {
  failures.push(`最慢一步只有 ${topStepDays.toFixed(2)} 天 —— why 里"后期单级以天计"那句不成立`)
}
if (failures.length > 0) {
  console.error('[calibrate-tech-time][FAIL] ' + failures.join('；'))
  process.exit(1)
}
console.log('[calibrate-tech-time] 两条自证通过：全树时长落在 50%~70% 区间内、末级单步以天计。')
