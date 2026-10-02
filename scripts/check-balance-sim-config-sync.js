/**
 * 判据：balance-sim 的 f2p 模拟里，凡是与配置表重复的数值都必须从表读，不得硬编码。
 *       2026-10-02 盯五处：capBase（#594）、initCap 与 producers（#596）、仓库与主城造价（#598）、初始资源 initAmount（#599）。
 *
 * 背景（收口清单 #592~#596）：模拟器原来把仓容基数硬编码成 1000，而 building.json 已经是 8000；
 * initCap 与 producers 也是字面量。两边漂移时模拟器照常输出一整套读数，却全是按旧值算的，
 * 而没有任何测试会红 —— #592/#593 连续两轮的结论就建立在错的输入上。
 *
 * 为什么必须是门：模拟器是 B02 全部数值读数的量具。量具与被量的表漂移时，后面每一条
 * 「实测 N 级 / 溢出 X」的结论都不可信 —— 而人不会每次跑之前先去看它读的是哪张表。
 *
 * 本门两种红法：① 该读表的地方退回硬编码；② 读表了，但读的不是对应那张表 / 那一行。
 * 扫档时显式传 --cap-base 不算违规：那是有意的命令行覆盖，不是默认值。
 */
const fs = require('fs')

const NL = String.fromCharCode(10)
const cliPath = 'server/tools/balance-sim/src/main/java/com/ironoath/battle/sim/BalanceCli.java'
const buildingPath = 'contract/config/building.json'
const resourcePath = 'contract/config/resource.json'

const cli = fs.readFileSync(cliPath, 'utf8')
const lines = cli.split(/\r?\n/)
const building = JSON.parse(fs.readFileSync(buildingPath, 'utf8'))
const resource = JSON.parse(fs.readFileSync(resourcePath, 'utf8'))

const fails = []
const fail = (m) => fails.push(m)

/** 取某行附近的上下文窗口（往上 12 行、往下 4 行） */
function around(matchIdx, before = 12, after = 4) {
  return lines.slice(Math.max(0, matchIdx - before), matchIdx + after + 1).join(NL)
}

// ---------- ① capBase（#594）----------
const warehouse = building.rows.find((r) => r.id === 'warehouse')
if (!warehouse) {
  console.error('[check-balance-sim-config-sync] building.json 里没有 warehouse 行')
  process.exit(1)
}
const capIdx = lines.findIndex((l) => l.includes('getOrDefault') && l.includes('"cap-base"'))
if (capIdx < 0) {
  fail('找不到 --cap-base 的读取点：f2p 的仓容截断维度依赖它，删掉会让 --cap-base 静默失效。')
} else {
  const capSnip = around(capIdx)
  const capHard = capSnip.match(/getOrDefault\(\s*"cap-base"\s*,\s*"(\d+)"/)
  if (capHard) {
    fail('capBase 又变回硬编码字面量了：当前 ' + capHard[1] + ' / 表里 ' + warehouse.capBase
      + '。修法：从 BuildingCfg 的 warehouse 行取 capBase()。')
  } else if (!/BuildingCfg\.class\s*,\s*"warehouse"/.test(capSnip)) {
    fail('capBase 的默认值没有从 BuildingCfg 的 warehouse 行读。退回去等于把 #592/#593 的错源再装回去。')
  }
}

// ---------- ② initCap（#596）----------
const initCapIdx = lines.findIndex((l) => /final long\[\]\s+initCap\s*=/.test(l))
if (initCapIdx < 0) {
  fail('找不到 initCap 的声明：仓容公式 capacity = initCap + capBase × 等级 依赖它。')
} else {
  const initSnip = lines.slice(initCapIdx, initCapIdx + 9).join(NL)
  const initHard = initSnip.match(/\{\s*\d+L\s*,/)
  if (initHard) {
    fail('initCap 又变回硬编码字面量了（看到 {20000L, … 这种行）。'
      + '修法：从 ResourceCfg 的 WOOD / STONE / IRON / GRAIN 取 initCap()。')
  } else {
    const want = ['WOOD', 'STONE', 'IRON', 'GRAIN']
    const got = [...initSnip.matchAll(/ResourceCfg\.class\s*,\s*"(\w+)"/g)].map((m) => m[1])
    if (got.length === 0) {
      fail('initCap 没有从 ResourceCfg 读（找不到任何 ResourceCfg.class 的取值）。')
    } else {
      for (const w of want) {
        if (!got.includes(w)) {
          fail('initCap 没有取 ' + w + '（当前只取了 ' + got.join(' / ') + '）'
            + ' —— 少一种资源会让它的容量永远停在初始值。')
        }
      }
    }
  }
}

// ---------- ③ producers（#596）----------
const prodIdx = lines.findIndex((l) => /final long\[\]\[\]\s+producers\s*=/.test(l))
if (prodIdx < 0) {
  fail('找不到 producers 的声明：四座采集建筑的 req / outBase / cost 全靠它。')
} else {
  const prodSnip = around(prodIdx, 12, 1)
  const prodHard = prodSnip.match(/\{\s*\d+L\s*,\s*\d+L\s*,/)
  if (prodHard) {
    fail('producers 又变回硬编码字面量了（看到 {1L, 120L, … 这种行）。修法：按 id 从 BuildingCfg 逐项取。')
  } else if (!/BuildingCfg\.class\s*,\s*producerIds\[i\]/.test(prodSnip)) {
    fail('producers 没有按 id 从 BuildingCfg 读（期望 configs.get(BuildingCfg.class, producerIds[i])）。')
  } else {
    const idIdx = lines.findIndex((l) => l.includes('producerIds = {'))
    const ids = idIdx < 0
      ? []
      : [...lines.slice(idIdx, idIdx + 3).join(NL).matchAll(/"(\w+)"/g)].map((m) => m[1])
    for (const id of ['lumber_camp', 'quarry', 'farm', 'iron_mine']) {
      if (!ids.includes(id)) {
        fail('producers 少了 ' + id + '（当前 ' + (ids.join(' / ') || '空')
          + '）—— 少一座采集建筑等于少一路产出。')
      }
    }
  }
}

// ---------- ④ 升级造价（#598）----------
// 仓库 600/300 与主城 1000 原来各写一份字面量。主城那处还顺带藏了个假设：
// `long stoneCost = woodCost;` 等价于断言「主城木石同价」—— 表里确实是 1000/1000，
// 但那是巧合不是约束，哪天策划把石头改成 1200 就会静默按 1000 算。
const costChecks = [
  { name: '仓库升级木价', re: /warehouseCfg\.costBaseWood\(\)/, hint: '仓库造价要读 warehouseCfg.costBaseWood()' },
  { name: '仓库升级石价', re: /warehouseCfg\.costBaseStone\(\)/, hint: '仓库造价要读 warehouseCfg.costBaseStone()' },
  { name: '主城升级木价', re: /mainCityCfg\.costBaseWood\(\)/, hint: '主城造价要读 mainCityCfg.costBaseWood()' },
  { name: '主城升级石价', re: /mainCityCfg\.costBaseStone\(\)/, hint: '主城造价要读 mainCityCfg.costBaseStone()' },
]
for (const c2 of costChecks) {
  if (!c2.re.test(cli)) fail(c2.name + ' 没从配置表读（' + c2.hint + '）—— 改表不动这里，读数就静默按写死的旧值算。')
}
// 「stoneCost = woodCost」这条隐含假设本身也要判红
if (/long\s+stoneCost\s*=\s*woodCost\s*;/.test(cli)) {
  fail('主城石价被写成 stoneCost = woodCost —— 那等价于断言「主城木石同价」。'
    + '表里今天是 1000/1000，但那是巧合不是约束，改表就会静默按旧值算。')
}
// ---------- ⑤ 初始资源 initAmount（#599）----------
// 紧挨着它的 woodRate / stoneRate / ... 早就读表了，只有初始资源这一行自己记了
// 5000/5000/2000/8000 —— 于是「改了 resource.json 的 initAmount，模拟器照按旧初始量跑」。
const initIdx2 = lines.findIndex((l) => /long\s+wood\s*=\s*configs\.get/.test(l))
if (initIdx2 < 0) {
  const legacy = lines.find((l) => /long\s+wood\s*=\s*\d+L\s*,\s*stone\s*=\s*\d+L/.test(l))
  fail('初始资源没有从 ResourceCfg 读'
    + (legacy ? '（仍是一行字面量：' + legacy.trim().slice(0, 60) + '）' : '')
    + ' —— 改 resource.json 的 initAmount，模拟器会静默按旧初始量跑。')
} else {
  const init2Snip = lines.slice(initIdx2, initIdx2 + 5).join(NL)
  const got2 = [...init2Snip.matchAll(/ResourceCfg\.class\s*,\s*"(\w+)"/g)].map((m) => m[1])
  for (const w of ['WOOD', 'STONE', 'IRON', 'GRAIN']) {
    if (!got2.includes(w)) {
      fail('初始资源没有取 ' + w + '（当前只取了 ' + (got2.join(' / ') || '空') + '）'
        + ' —— 少一种会让零氪起点少一份资源。')
    }
  }
}
if (fails.length) {
  console.error('[check-balance-sim-config-sync][FAIL] balance-sim 与配置表脱钩了：')
  for (const f of fails) console.error('  - ' + f)
  console.error('')
  console.error('  后果：模拟器照常输出一整套读数，却全是按写死的旧值算的，没有测试会红。')
  console.error('  依据：收口清单 #592~#596。')
  process.exit(1)
}

// ---- #664: the 7th hard-coded site. The `forge` cost used two literals,
// `FORGE_BASE = 700000L` and `FORGE_RATIO = 12200L`, which are exactly
// `curve.EQUIP_FORGE_COST`'s base (70) and ratio (1.22) after fixed-point scaling.
// Two problems, one of them a written rule:
//   (a) CurveCfg's class javadoc says 代码中不得出现任何曲线常量（铁律 1）— a literal is that.
//   (b) Edit curve.json and the simulator keeps printing a full set of readings computed
//       from the old numbers, and nothing turns red (same family as #592 capBase).
// The exponent is deliberately NOT checked: the production calibration test passes
// `level - 1` because ITS level is 1-based, while BalanceCli's `lvl` is 0-based. Copying that
// `- 1` throws `geometric 的指数不得为负：-1` on day one — recorded in 收口清单 #664.
const cliCode = cli
  .split('\n')
  .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
  .map((l) => l.split('//')[0])
  .join('\n')
const forgeProblems = []
if (/FORGE_BASE|FORGE_RATIO/.test(cliCode)) {
  forgeProblems.push('强化费又出现了写死的 FORGE_BASE / FORGE_RATIO 字面量')
}
if (!/configs\.curve\(\s*"EQUIP_FORGE_COST"\s*\)/.test(cliCode)) {
  forgeProblems.push('强化费没有从 configs.curve("EQUIP_FORGE_COST") 读')
}
if (!/forgeCurve\.baseFixed\(\)/.test(cliCode) || !/forgeCurve\.ratioFixed\(\)/.test(cliCode)) {
  forgeProblems.push('强化费没有用 curve 的 baseFixed / ratioFixed')
}
if (forgeProblems.length > 0) {
  console.error('[check-balance-sim-config-sync][FAIL] 强化费口径脱钩：')
  for (const p of forgeProblems) console.error('  - ' + p)
  console.error('  后果：CurveCfg 类注释明写「代码中不得出现任何曲线常量（铁律 1）」，'
    + '且表一改模拟器照常按旧值报数。依据：收口清单 #664。')
  process.exit(1)
}
// ---- #665: the 8th site, and the only one so far that is a FORMULA mismatch rather than a
// literal copy. Warehouse capacity was `initCap + capBase x level` (linear) while production
// `ResourceRateService` uses `Formula.buildingOutput(cfg.capBase(), level, outputExponent)`
// i.e. `capBase x level^1.08`. building.json's designNote says so explicitly:
// 「容量走 BUILDING_OUTPUT 曲线（POWER，指数 1.08）…『容量 ÷ 每小时产量』这个比值与等级无关,
//   恒等于 capBase/120 ≈ 8.3 小时」and warehouse.why quotes `20000 + 1000x16^1.08` for #592.
// Effect at 120 days: grain overflow 471200 -> 0 and main city 15 -> 16 levels.
// The gate must pin the formula, not just the base, because #592 only checked the base.
const capProblems = []
const linearCap = /initCap\[\w+\]\s*\+\s*\w*[Cc]apBase\s*\*/.test(cliCode)
if (linearCap) {
  capProblems.push('仓容又变回线性 `initCap + capBase × 等级`（线上走 capBase × level^1.08）')
}
if (!/Formula\.buildingOutput\(/.test(cliCode)) {
  capProblems.push('仓容没有走 core 的 Formula.buildingOutput')
}
if (!/curve\(\s*"BUILDING_OUTPUT"\s*\)\.exponentFixed\(\)/.test(cliCode)) {
  capProblems.push('仓容的指数没有取 curve.BUILDING_OUTPUT 的定点 exponentFixed')
}
if (capProblems.length > 0) {
  console.error('[check-balance-sim-config-sync][FAIL] 仓容公式形状脱钩：')
  for (const p of capProblems) console.error('  - ' + p)
  console.error('  后果：线性公式让「能囤几小时」随等级递减，而线上恒为 capBase/120 ≈ 8.3 小时；'
    + '实测 120 天粮溢出 471200 → 0、主城 15 → 16 级。依据：收口清单 #665。')
  process.exit(1)
}
const r = (id) => {
  const x = resource.rows.find((y) => y.id === id)
  return x ? x.initCap : '?'
}
console.log('[check-balance-sim-config-sync] 八处都与配置表同源（capBase ' + warehouse.capBase
  + ' / initCap ' + ['WOOD', 'STONE', 'IRON', 'GRAIN'].map(r).join(',')
  + ' / producers 4 座 / 仓库与主城造价各从表读 / 初始资源读 initAmount / 强化费读 EQUIP_FORGE_COST 曲线）。')