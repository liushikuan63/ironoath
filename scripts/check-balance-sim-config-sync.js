/**
 * 判据：balance-sim 的 f2p 模拟里，凡是与配置表重复的数值都必须从表读，不得硬编码。
 *       2026-10-02 盯三处：capBase（#594）、initCap、producers（#596）。
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

if (fails.length) {
  console.error('[check-balance-sim-config-sync][FAIL] balance-sim 与配置表脱钩了：')
  for (const f of fails) console.error('  - ' + f)
  console.error('')
  console.error('  后果：模拟器照常输出一整套读数，却全是按写死的旧值算的，没有测试会红。')
  console.error('  依据：收口清单 #592~#596。')
  process.exit(1)
}

const r = (id) => {
  const x = resource.rows.find((y) => y.id === id)
  return x ? x.initCap : '?'
}
console.log('[check-balance-sim-config-sync] 三处都与配置表同源（capBase ' + warehouse.capBase
  + ' / initCap ' + ['WOOD', 'STONE', 'IRON', 'GRAIN'].map(r).join(',')
  + ' / producers 4 座）。')