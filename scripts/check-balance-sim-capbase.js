/**
 * 判据：BalanceCli 的 f2p 模拟必须**从配置表读 warehouse.capBase**，而不是把它硬编码成常量。
 *       两种违规都会红：① 硬编码字面量默认值；② 根本读不到 warehouse 行（改法走偏）。
 *
 * 背景（收口清单 #592~#594）：模拟器原来把仓容基数硬编码成 1000，而 building.json 的
 * warehouse.capBase 已经是 8000 —— 两边漂移时**模拟器照常输出一整套读数**，却全是按 1000
 * 算的。#592/#593 连续两轮的结论就建立在错的输入上，而没有任何测试会红。
 *
 * 为什么必须是门：模拟器是 B02 全部数值读数的量具。量具与被量的表漂移时，后面每一条
 * 「实测 N 级 / 溢出 X」的结论都不可信 —— 而人不会每次跑之前先去看它读的是哪张表。
 *
 * 扫档时显式传 --cap-base 不算违规：那是有意的命令行覆盖，不是默认值。
 */
const fs = require('fs')

const cliPath = 'server/tools/balance-sim/src/main/java/com/ironoath/battle/sim/BalanceCli.java'
const cfgPath = 'contract/config/building.json'

const cli = fs.readFileSync(cliPath, 'utf8')
const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'))
const warehouse = cfg.rows.find((r) => r.id === 'warehouse')
if (!warehouse) {
  console.error('[check-balance-sim-capbase] building.json 里没有 warehouse 行')
  process.exit(1)
}

// 取 --cap-base 那一行（允许跨行，因为默认值可能是 Long.toString(...) 这种表达式）
const lines = cli.split(/\r?\n/)
const idx = lines.findIndex((l) => l.includes('getOrDefault') && l.includes('"cap-base"'))
if (idx < 0) {
  console.error('[check-balance-sim-capbase][FAIL] 找不到 --cap-base 的读取点')
  console.error('  f2p 模拟的仓容截断维度依赖它；删掉这一行会让 --cap-base 静默失效。')
  process.exit(1)
}
// 把紧随其后的两行也算进来，getOrDefault 可能换行写
const snippet = lines.slice(Math.max(0, idx - 10), idx + 3).join(String.fromCharCode(10))

const hardcoded = snippet.match(/getOrDefault\(\s*"cap-base"\s*,\s*"(\d+)"/)
if (hardcoded) {
  console.error('[check-balance-sim-capbase][FAIL] --cap-base 的默认值又变回硬编码字面量了：')
  console.error(`  当前值 = ${hardcoded[1]}`)
  console.error(`  building.json warehouse.capBase = ${warehouse.capBase}`)
  console.error('  两边漂移时模拟器照常输出读数，却全是按硬编码值算的（#592/#593 的教训）。')
  console.error('  修法：默认值从 configs.get(BuildingCfg.class, "warehouse").capBase() 取。')
  process.exit(1)
}

if (!/BuildingCfg\.class\s*,\s*"warehouse"/.test(snippet)) {
  console.error('[check-balance-sim-capbase][FAIL] --cap-base 的默认值没有从配置表读')
  console.error('  期望形如 options.getOrDefault("cap-base", Long.toString(capBaseFromCfg))，')
  console.error('  其中 capBaseFromCfg = configs.get(BuildingCfg.class, "warehouse").capBase()。')
  console.error('  这次是 2026-10-02 #594 定的改法；退回去等于把 #592/#593 的错源再装回去。')
  process.exit(1)
}

console.log(`[check-balance-sim-capbase] 模拟器从配置表读仓容基数（building.json warehouse.capBase = ${warehouse.capBase}）。`)