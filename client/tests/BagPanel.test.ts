/**
 * 职责：BagPanel 的单测 —— B04 §2（产出明细是「转化关键 UI」）、§3（背包排序）、
 * 验收 1/11（满仓警告）、验收 5（Σ明细 = 实际产量，误差 0）、验收 6（受保护量）。
 * 依赖：node:test / node:assert。
 *
 * <p>最重要的一条断言是 {@code sumMatches}：产出明细加起来和总产量对不上，
 * 是玩家自己拿计算器加一遍就能发现的数值造假。所以不吻合时不仅要打日志，
 * 面板文案里也必须明说 —— 悄悄显示一个对不上的明细，比不显示明细更糟。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildBagPanel, buildItemRow, buildOutputLine, buildResourcePanel, buildResourceRow,
  chestReceiptText, itemTypeText,
} from '../assets/scripts/game/bag/BagPanel'
import { formatRate } from '../assets/scripts/game/gacha/GachaDisclosure'
import { percentText } from '../assets/scripts/core/FixedPoint'
import type {
  BagItem, BagListResp, OutputBreak, ResourceDetail, ResourceDetailResp,
} from '../assets/scripts/net/generated/BagProtocol'

function output(source: string, amount: number, percentFixed: number | null = null): OutputBreak {
  return { source, amount, isPercent: percentFixed !== null, percentFixed }
}

function detail(overrides: Partial<ResourceDetail> = {}): ResourceDetail {
  return {
    type: 'WOOD',
    current: 3400,
    cap: 12000,
    protectedAmount: 800,
    perHour: 720,
    lastSettle: 0,
    full: false,
    // 600 + 120 = 720，与 perHour 精确相等（验收 5 要求误差 0）
    breakdown: [output('农田 Lv8', 600), output('科技加成', 120, 1000)],
    ...overrides,
  }
}

function detailResp(resources: ResourceDetail[]): ResourceDetailResp {
  return { resources, serverNow: 0 }
}

function item(overrides: Partial<BagItem> = {}): BagItem {  return {
    itemId: 'item_speed_60m',
    name: '加速 60 分钟',
    type: 'SPEEDUP',
    rarity: 'R',
    obtainFrom: '第七章宝箱',
    count: 3,
    stackMax: 10,
    sortKey: 100,
    effectKind: 'REDUCE_BUILD_SECONDS',
    effectTarget: null,
    ...overrides,
  }
}

function bagResp(items: BagItem[], used = 3, max = 100): BagListResp {
  return { items, capacityUsed: used, capacityMax: max }
}

// ---------- 产出明细（验收 5） ----------

test('明细之和精确等于实际产量时，文案不带「不符」', () => {
  const row = buildResourceRow(detail())
  assert.equal(row.sumMatches, true)
  assert.equal(row.sumText, '合计 每小时 +720')
  assert.equal(row.perHourText, '每小时 +720')
  assert.equal(row.lines.length, 2)
})

test('明细之和对不上时必须在面板文案里明说，不能悄悄显示', () => {
  const warned: string[] = []
  const originalWarn = console.warn
  console.warn = (message: unknown) => {
    warned.push(String(message))
  }
  try {
    // 服务端不变量被破坏：明细只有 600，实际产量却是 720
    const row = buildResourceRow(detail({ breakdown: [output('农田 Lv8', 600)] }))
    assert.equal(row.sumMatches, false)
    assert.equal(row.sumText, '合计 每小时 +600（与实际产量 720 不符，已上报）')
    assert.equal(warned.length, 1)
    assert.match(warned[0] ?? '', /误差为 0/)
  } finally {
    console.warn = originalWarn
  }
})

test('百分比行同时给出绝对值与百分比；百分比缺失时照实说，不编一个数', () => {
  assert.equal(buildOutputLine(output('农田 Lv8', 600)).text, '+600')
  assert.equal(buildOutputLine(output('科技加成', 120, 1000)).text, '+120 (+10%)')
  assert.equal(buildOutputLine(output('科技加成', 120, 150)).text, '+120 (+1.5%)')
  assert.equal(buildOutputLine({ source: '异常行', amount: 120, isPercent: true, percentFixed: null }).text,
    '+120（百分比缺失）')
})

test('percentText 与抽卡合规公示的 formatRate 对同一输入给出相同字符串', () => {
  // 两处实现（一个纯整数、一个复用 FixedPoint.format）必须口径一致，
  // 否则同一个 10% 在抽卡页显示 "10%"、在产出页显示 "10.0%"，玩家会怀疑其中一个是假的
  for (const fixed of [0, 25, 100, 150, 600, 1000, 2500, 10000]) {
    assert.equal(percentText(fixed), formatRate(fixed), `fixed=${fixed}`)
  }
})

test('满仓要标红并汇总提示：产出停了而玩家不知道，他会以为产量被偷偷改了', () => {
  const row = buildResourceRow(detail({ current: 12000, full: true }))
  assert.equal(row.full, true)
  assert.equal(row.fullText, '已满仓，停产')

  const panel = buildResourcePanel(detailResp([
    detail({ type: 'WOOD', full: true, current: 12000 }),
    detail({ type: 'STONE' }),
    detail({ type: 'GRAIN', full: true, current: 9000, cap: 9000 }),
  ]))
  assert.equal(panel.fullWarning, '木材、粮草 已满仓，产出已停止。扩建仓库或消耗掉一部分后才会恢复')
  // 负向断言：这句话是**直接印给玩家**的（真截图上曾写着「STAMINA 已满仓」），
  // 所以屏上一个资源码都不许出现。只钉正向中文名的话，将来加一种资源又漏翻是抓不到的。
  for (const code of ['WOOD', 'STONE', 'IRON', 'GRAIN', 'GOLD', 'STAMINA']) {
    assert.ok(!panel.fullWarning!.includes(code), `满仓提示里还有裸资源码 ${code}`)
  }
  assert.equal(buildResourcePanel(detailResp([detail()])).fullWarning, null)
})

test('受保护量为 0 时不显示（「受保护 0」只是噪音），大于 0 时是验收 6 的可见出口', () => {
  assert.equal(buildResourceRow(detail({ protectedAmount: 0 })).protectedText, null)
  assert.equal(buildResourceRow(detail({ protectedAmount: 800 })).protectedText, '受保护 800')
})

test('资源行顺序照搬服务端，不排序', () => {
  const panel = buildResourcePanel(detailResp([
    detail({ type: 'GRAIN' }), detail({ type: 'WOOD' }), detail({ type: 'IRON' }),
  ]))
  assert.deepEqual(panel.rows.map((row) => row.type), ['GRAIN', 'WOOD', 'IRON'])
})

// ---------- 背包（B04 §3） ----------

test('道具顺序与分页都照搬服务端：页内不重排，页签顺序 = 类型首次出现的顺序', () => {
  const panel = buildBagPanel(bagResp([
    item({ itemId: 'c', type: 'CHEST', sortKey: 300 }),
    item({ itemId: 'a', type: 'SPEEDUP', sortKey: 100 }),
    item({ itemId: 'd', type: 'CHEST', sortKey: 400 }),
    item({ itemId: 'b', type: 'SPEEDUP', sortKey: 200 }),
  ]))
  assert.deepEqual(panel.pages.map((page) => page.type), ['CHEST', 'SPEEDUP'],
    '页签顺序由服务端排序后的首次出现顺序决定，客户端不自己定')
  assert.deepEqual(panel.pages[0]?.items.map((row) => row.itemId), ['c', 'd'],
    '页内顺序必须严格保持 sortKey 升序 —— 客户端再排一次就会出现双端不一致')
  assert.deepEqual(panel.pages[1]?.items.map((row) => row.itemId), ['a', 'b'])
})

test('加速类道具需要先选目标（B04 §4），其余类型点了直接生效', () => {
  assert.equal(buildItemRow(item({ type: 'SPEEDUP' })).needsTarget, true)
  for (const type of ['RESOURCE', 'CHEST', 'MATERIAL', 'BUFF']) {
    assert.equal(buildItemRow(item({ type })).needsTarget, false, type)
  }
})

test('来源提示：配置了才显示，未配置（null 或空串）不显示', () => {
  assert.equal(buildItemRow(item({ obtainFrom: '第七章宝箱' })).obtainText, '来自：第七章宝箱')
  assert.equal(buildItemRow(item({ obtainFrom: null })).obtainText, null)
  assert.equal(buildItemRow(item({ obtainFrom: '' })).obtainText, null)
})

test('道具名字照搬服务端下发的中文，客户端不翻译', () => {
  assert.equal(buildItemRow(item({ name: '加速 60 分钟', count: 3 })).title, '加速 60 分钟 ×3')
  assert.equal(buildItemRow(item()).stackText, '3/10')
  assert.equal(buildItemRow(item()).rarityText, 'R')
})

test('背包容量与满包提示', () => {
  assert.equal(buildBagPanel(bagResp([], 3, 100)).capacityText, '背包 3/100')
  assert.equal(buildBagPanel(bagResp([], 3, 100)).capacityFull, false)
  assert.equal(buildBagPanel(bagResp([], 100, 100)).capacityFull, true)
})

test('道具类型页签名：认识的翻译成中文，不认识的原样显示（静默变成空白页会让玩家以为道具丢了）', () => {
  assert.equal(itemTypeText('SPEEDUP'), '加速')
  assert.equal(itemTypeText('RESOURCE'), '资源')
  assert.equal(itemTypeText('CHEST'), '宝箱')
  assert.equal(itemTypeText('MATERIAL'), '材料')
  assert.equal(itemTypeText('BUFF'), '增益')
  assert.equal(itemTypeText('SOMETHING_NEW'), 'SOMETHING_NEW')
})

test('空背包也能组装（新号第一次打开面板）', () => {
  const panel = buildBagPanel(bagResp([]))
  assert.deepEqual(panel.pages, [])
  assert.equal(panel.capacityFull, false)
})

test('行里带**结构化数量**：批量开箱的入参取自 `count`，不是从 "3/10" 这种显示串里拆出来的', () => {
  // 持有 7 个、单堆上限 999：显示串是 "7/999"，而数字必须是 7。
  // 这条断言护的是"显示串兼职当数据源"那类隐性耦合：将来 stackText 改成 "7 / 999" 或加千分位，
  // 拆字符串的写法会**静默**算错数量（多开或漏开），而结构化字段不受影响。
  const row = buildItemRow(item({ type: 'CHEST', count: 7, stackMax: 999 }))
  assert.equal(row.count, 7)
  assert.equal(row.stackText, '7/999')
  // 数量为 0 的行也照实传 0（视图据此不给「全开」；不许在这里悄悄改成 1）
  assert.equal(buildItemRow(item({ type: 'CHEST', count: 0 })).count, 0)
})

test('开箱回执把"开了几个 / 开出什么"照服务端说的念', () => {
  const text = chestReceiptText({
    consumed: 10, serverNow: 1, mailId: null, seed: 7,
    results: [{ type: 'RESOURCE', id: 'WOOD', count: 3000, name: '木材' },
      { type: 'HERO_FRAGMENT', id: 'h1', count: 5, name: '卫无咎碎片' }],
    overflow: [],
  })
  assert.equal(text, '开了 10 个：木材 ×3000 · 卫无咎碎片 ×5')
})

test('装不下的那部分必须说"已转邮件"—— 不说就等于玩家以为道具丢了（B04 验收 2）', () => {
  const text = chestReceiptText({
    consumed: 100, serverNow: 1, mailId: 'm-1', seed: 7,
    results: [{ type: 'RESOURCE', id: 'GOLD', count: 100, name: '金币' }],
    overflow: [{ type: 'ITEM', id: 'item_x', count: 2, name: '加速道具（1 小时）' }],
  })
  assert.match(text, /开了 100 个：金币 ×100/)
  assert.match(text, /加速道具（1 小时） ×2 装不下，已转邮件/)
})

test('一次开出空也说实话，不留一行空白当"成功"', () => {
  assert.equal(chestReceiptText({
    consumed: 1, serverNow: 1, mailId: null, seed: 7, results: [], overflow: [],
  }), '开了 1 个：什么都没有')
})
