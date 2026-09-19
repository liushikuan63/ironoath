/**
 * 职责：抽卡概率公示面板的单测 —— B06 验收 3「客户端概率面板数值与配置完全一致（同源校验）」。
 * 依赖：node:test / node:assert / node:fs。
 *
 * 「同源校验」的关键在于<b>读真实的 contract/config/gacha.json</b>，而不是读一份手抄的夹具。
 * 手抄的夹具会随配置漂移 —— B05 就吃过这个亏（战斗测试里手抄的克制矩阵漏了一条关系，
 * 测出来的胜率全是假的）。所以这里直接读仓库里的真表，
 * 服务端改了概率而客户端面板没跟上，这条测试就会红。
 *
 * <p><b>喂给面板的是 `/gacha/probability` 那个形状</b>（`cfgToProb` 负责按服务端的搬法把表行映射成响应）：
 * 这一层原先直接吃 `GachaCfg`，而客户端只有那份表的类型、没有数据 —— 结果是它在运行期喂不进来，
 * 公示面板从建好那天起没被挂过。改成吃响应之后，测试仍然对真表负责，但对得上运行期的那条路径。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { resolve, join } from 'node:path'
import {
  buildDisclosure, formatRate,
} from '../assets/scripts/game/gacha/GachaDisclosure'
import type { GachaDisclosure } from '../assets/scripts/game/gacha/GachaDisclosure'
import type { GachaCfg } from '../assets/scripts/config/generated/ConfigTypes'
import type { GachaProbResp } from '../assets/scripts/net/generated/HeroProtocol'

interface GachaTable {
  table: string
  version: number
  rows: Array<Record<string, unknown>>
}

/**
 * 从仓库里读真实的 gacha 表。
 *
 * 测试跑在 client/ 目录下（scripts/test-client.sh 会先 cd 进去），
 * 但为了在别的 cwd 下也能跑，这里逐级向上找仓库根。
 */
function loadRealTable(file: string): GachaTable {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    const candidate = join(dir, 'contract', 'config', file)
    if (existsSync(candidate)) {
      return JSON.parse(readFileSync(candidate, 'utf8')) as GachaTable
    }
    dir = resolve(dir, '..')
  }
  throw new Error(`找不到 contract/config/${file}，无法做同源校验`)
}

/** 配置里的 DECIMAL 列是十进制字符串，生成后是定点 long；测试里按同样口径转换。 */
function toFixed(decimal: unknown): number {
  const value = typeof decimal === 'string' ? decimal : String(decimal)
  const scaled = Math.round(Number(value) * 10000)
  assert.ok(Number.isInteger(scaled), `定点转换失败: ${value}`)
  return scaled
}

function rowToCfg(row: Record<string, unknown>): GachaCfg {
  return {
    id: String(row.id),
    name: String(row.name),
    poolType: String(row.poolType) as GachaCfg['poolType'],
    // exactOptionalPropertyTypes 下不能给可选属性赋 undefined，只能用条件展开决定「有没有这个键」
    ...(row.costItemId === undefined ? {} : { costItemId: String(row.costItemId) }),
    ...(row.costResource === undefined ? {} : { costResource: String(row.costResource) }),
    ...(row.upHeroId === undefined ? {} : { upHeroId: String(row.upHeroId) }),
    costCount: Number(row.costCount),
    lifetimeLimit: Number(row.lifetimeLimit),
    ssrChance: toFixed(row.ssrChance),
    ssrBaseChance: toFixed(row.ssrBaseChance),
    srChance: toFixed(row.srChance),
    srBaseChance: toFixed(row.srBaseChance),
    rChance: toFixed(row.rChance),
    rBaseChance: toFixed(row.rBaseChance),
    nChance: toFixed(row.nChance),
    nBaseChance: toFixed(row.nBaseChance),
    ssrPity: Number(row.ssrPity),
    srPity: Number(row.srPity),
    disclosureText: String(row.disclosureText),
  }
}

/** item 表的行 id → 中文名（服务端 `itemName` 查的就是这一列）。 */
const itemNames = new Map<string, string>(
  loadRealTable('item.json').rows.map((r) => [String(r.id), String(r.name)]),
)

/**
 * 按服务端 `probability()` 的搬法把表行映射成响应。
 *
 * <p>映射里刻意做同一件事：道具计价时把 item 表的中文名一起搬进 `costItemName` ——
 * 面板那一行不许出现 `item_chest_hero` 这种行 id（#255/#268/#281/#291 同族）。
 */
function cfgToProb(pool: GachaCfg): GachaProbResp {
  return {
    poolId: pool.id,
    name: pool.name,
    poolType: pool.poolType,
    items: [],
    tierRates: [
      { rarity: 'SSR', rateFixed: pool.ssrChance },
      { rarity: 'SR', rateFixed: pool.srChance },
      { rarity: 'R', rateFixed: pool.rChance },
      { rarity: 'N', rateFixed: pool.nChance },
    ],
    pityRule: { ssrPity: pool.ssrPity, srPity: pool.srPity, ssrUpGuarantee: 0 },
    disclosureText: pool.disclosureText,
    costItemId: pool.costItemId ?? null,
    costItemName: pool.costItemId === undefined ? null : itemNames.get(pool.costItemId) ?? null,
    costCount: pool.costCount,
    lifetimeLimit: pool.lifetimeLimit,
    serverNow: 0,
    costResource: pool.costResource ?? null,
  }
}

/** 面板读的是响应，所以测试统一走这一层。 */
function panel(pool: GachaCfg): GachaDisclosure {
  return buildDisclosure(cfgToProb(pool))
}

const table = loadRealTable('gacha.json')
const pools = table.rows.map(rowToCfg)

/** noUncheckedIndexedAccess 下下标访问得到的是 T | undefined，用这个辅助把它收窄掉。 */
function poolAt(index: number): GachaCfg {
  const pool = pools[index]
  assert.ok(pool !== undefined, `配置里应当至少有 ${index + 1} 个卡池`)
  return pool
}

/** 去掉 costResource 键（不能赋 undefined，exactOptionalPropertyTypes 会报错）。 */
function withoutCostResource(pool: GachaCfg): GachaCfg {
  const clone: Record<string, unknown> = { ...pool }
  delete clone.costResource
  return clone as unknown as GachaCfg
}

// ---------- 验收 3：面板与配置同源 ----------

test('验收3：每个卡池的面板数值与 gacha 表逐项一致，一个数字都不许自己算', () => {
  assert.ok(pools.length >= 3, `真实配置里应当至少有 3 个卡池，实际 ${pools.length}`)
  for (const pool of pools) {
    const view = panel(pool)
    assert.equal(view.poolId, pool.id)
    assert.equal(view.poolName, pool.name)
    assert.equal(view.ssrPity, pool.ssrPity)
    assert.equal(view.srPity, pool.srPity)

    const byRarity = new Map(view.tiers.map((t) => [t.rarity, t.rateFixed]))
    assert.equal(byRarity.get('SSR'), pool.ssrChance, `${pool.id} 的 SSR 公示概率`)
    assert.equal(byRarity.get('SR'), pool.srChance, `${pool.id} 的 SR 公示概率`)
    assert.equal(byRarity.get('R'), pool.rChance, `${pool.id} 的 R 公示概率`)
    assert.equal(byRarity.get('N'), pool.nChance, `${pool.id} 的 N 公示概率`)

    // 公示原文必须原样透传：合规要求「原文呈现，不得删减、折叠或以图标替代」
    assert.equal(view.disclosureText, pool.disclosureText)
  }
})

test('面板展示的是公示概率（含保底），不是每抽基础概率 —— 两者在真实配置里是不同的数', () => {
  for (const pool of pools) {
    const view = panel(pool)
    const ssr = view.tiers.find((t) => t.rarity === 'SSR')
    assert.ok(ssr !== undefined)
    assert.equal(ssr.rateFixed, pool.ssrChance)
    assert.notEqual(pool.ssrBaseChance, pool.ssrChance,
      `${pool.id}：基础概率与公示概率相等，说明保底没有参与校准（综合概率会超出公示值）`)
    assert.ok(pool.ssrBaseChance < pool.ssrChance,
      `${pool.id}：基础概率必须低于公示概率，差额由保底补上`)
    // 面板绝不能把基础概率显示出去：玩家实测到的出率会高于面板数字，同样构成公示不实
    assert.notEqual(ssr.rateFixed, pool.ssrBaseChance)
  }
})

test('四档公示概率之和必须恰为 100%，少一个定点单位就拒绝出面板', () => {
  for (const pool of pools) {
    const sum = pool.ssrChance + pool.srChance + pool.rChance + pool.nChance
    assert.equal(sum, 10000, `${pool.id} 的四档公示概率之和`)
    assert.doesNotThrow(() => panel(pool))
  }

  const first = poolAt(0)
  const broken: GachaCfg = { ...first, nChance: first.nChance - 1 }
  assert.throws(() => panel(broken), /恰为 10000/,
    '加起来 99.99% 的面板摆到玩家面前就是公示不实')
})

test('公示文案缺失时拒绝出面板：合规要求必须原文展示，没有文案就等于没有公示', () => {
  const first = poolAt(0)
  assert.throws(() => panel({ ...first, disclosureText: '' }), /缺少公示文案/)
  assert.throws(() => panel({ ...first, disclosureText: '   ' }), /缺少公示文案/)
})

test('计价方式必须恰好一种，且那一行只写中文名 —— 原先两条绿灯钉的就是行 id 与枚举原文', () => {
  const resourcePriced = pools.find((p) => p.costResource !== undefined)
  const itemPriced = pools.find((p) => p.costItemId !== undefined)
  assert.ok(resourcePriced !== undefined, '真实配置里应当有以资源计价的池子')
  assert.ok(itemPriced !== undefined, '真实配置里应当有以道具计价的限定池')

  // 这两条断言原先写的是 /GOLD/ 与 /item_chest_hero/ —— 与 #268 那两条
  // `'WOOD 12000/12000'`、#288 那条 `'season_01 赛季'` 同一种：测试替缺陷作证。
  assert.match(panel(resourcePriced).costText, /金币/)
  assert.ok(!panel(resourcePriced).costText.includes('GOLD'), '资源枚举原文不许上屏')
  assert.match(panel(itemPriced).costText, new RegExp(itemNames.get(itemPriced.costItemId ?? '') ?? ''))
  assert.ok(!panel(itemPriced).costText.includes('item_'), '道具行 id 不许上屏')

  assert.throws(() => panel({ ...resourcePriced, costItemId: 'item_gold_1000' }),
    /恰好指定一种计价方式/)
  assert.throws(() => panel(withoutCostResource(resourcePriced)),
    /恰好指定一种计价方式/)
})

test('终身限抽次数要展示出来：新手池限抽 1 次，玩家必须能在抽之前看到', () => {
  const newbie = pools.find((p) => p.lifetimeLimit > 0)
  assert.ok(newbie !== undefined, '真实配置里应当有限抽的池子')
  assert.equal(panel(newbie).lifetimeLimitText, `每个账号限抽 ${newbie.lifetimeLimit} 次`)

  const unlimited = pools.find((p) => p.lifetimeLimit === 0)
  assert.ok(unlimited !== undefined)
  assert.equal(panel(unlimited).lifetimeLimitText, '不限次数')
})

// ---------- 定点格式化：全程整数，不出现浮点 ----------

test('formatRate 用整数运算格式化，不会把 2% 显示成 1.9999999%', () => {
  assert.equal(formatRate(200), '2%')
  assert.equal(formatRate(600), '6%')
  assert.equal(formatRate(10000), '100%')
  assert.equal(formatRate(0), '0%')
  assert.equal(formatRate(150), '1.5%')
  assert.equal(formatRate(25), '0.25%')
  assert.equal(formatRate(1300), '13%')
  assert.equal(formatRate(5800), '58%')
  // 真实配置里的每一档都必须能格式化，且结果里不含浮点噪声
  for (const pool of pools) {
    for (const tier of panel(pool).tiers) {
      assert.equal(tier.text, formatRate(tier.rateFixed))
      assert.ok(!/[.]?\d{5,}/.test(tier.text), `${pool.id} ${tier.rarity} 的文本出现浮点噪声: ${tier.text}`)
    }
  }
})

test('formatRate 拒绝非整数与越界输入：定点数被还原成 double 时必须当场炸', () => {
  assert.throws(() => formatRate(199.99), /定点整数/,
    '1.9999% 这种输入说明上游已经把定点数还原成了 double')
  assert.throws(() => formatRate(-1), /越界/)
  assert.throws(() => formatRate(10001), /越界/)
})

test('档位顺序是 SSR→SR→R→N：公示面板从高到低展示，这个顺序本身就是合规口径', () => {
  for (const pool of pools) {
    assert.deepEqual(panel(pool).tiers.map((t) => t.rarity), ['SSR', 'SR', 'R', 'N'])
  }
})

test('服务端把表行原样搬进响应那两处还在原地（镜像保险）', () => {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (existsSync(join(dir, 'server'))) break
    dir = resolve(dir, '..')
  }
  const src = readFileSync(join(dir,
    'server/game-web/src/main/java/com/ironoath/web/service/GachaAppService.java'), 'utf8')
  assert.ok(/GachaPoolSummary\(pool\.id\(\), pool\.name\(\), pool\.poolType\(\)\.name\(\)/.test(src),
    '池列表那一行的搬法改了 ⇒ cfgToProb 这份映射要跟着改，否则本测试验的是别的形状')
  assert.ok(/pool\.disclosureText\(\), pool\.costItemId\(\),/.test(src),
    '公示原文与计价列的搬运顺序改了 ⇒ 同上')
})
