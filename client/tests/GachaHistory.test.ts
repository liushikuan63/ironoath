/**
 * 职责：抽卡记录纯逻辑用例（B15 §三 合规三件套的第三件：最近 N 次可查）。
 * 依赖：node:test + node:fs + game/gacha/GachaHistory（不碰 cc）。
 *
 * <p><b>每条钉的都是一个会做错的地方</b>：
 * ① 空记录必须说一句"还没有"，不能是一片空白（空白与"坏了"分不开）；
 * ② 时间只用 `serverNow` 算，本机时钟不参与 —— 跨时区或改过系统时间的机器上不许出现负数；
 * ③ 名字缺失给「未知武将 / 未知卡池」，**不把 `hero_guanyu` / `pool_std` 印给玩家**（#255/#268/#303 那一族）；
 * ④ 保底标记必须透传：公示里写了保底，玩家就要能在记录里验证它生效过；
 * ⑤ 分页越界要夹回最后一页而不是给空白页（抽完一轮后记录变多，旧页码可能已失效）；
 * ⑥ 全源码不出现 `Date.now` / `new Date` —— 引本机时钟是这条纪律最容易失守的方式。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { HISTORY_PAGE_SIZE, elapsedText, buildGachaHistory } from '../assets/scripts/game/gacha/GachaHistory'
import type { GachaHistoryRow, GachaHistoryView } from '../assets/scripts/game/gacha/GachaHistory'
import type { GachaRecord } from '../assets/scripts/net/generated/PayProtocol'

const NOW = 1_800_000_000_000

function record(overrides: Partial<GachaRecord> = {}): GachaRecord {
  return { time: NOW - 60_000, poolId: 'pool_std', heroId: 'hero_guanyu', isPity: false, ...overrides }
}

const NAMES = {
  poolNames: new Map([['pool_std', '名将招募']]),
  heroNames: new Map([['hero_guanyu', '关羽']]),
}

/** 取第 index 行：这是测试夹具自己的守卫，缺行时给出比 "possibly undefined" 更有用的话。 */
function rowAt(view: GachaHistoryView, index: number): GachaHistoryRow {
  const row = view.rows[index]
  assert.ok(row !== undefined, `第 ${index} 行不存在（共 ${view.rows.length} 行）`)
  return row
}

test('空记录：给一句"还没有"，不是空白', () => {
  const view = buildGachaHistory({ records: [], retentionDays: 90, serverNow: NOW }, NAMES)
  assert.equal(view.rows.length, 0)
  assert.notEqual(view.emptyText, null)
  assert.match(String(view.emptyText), /还没有/)
  // 空列表也算 1 页：0 页会让"上一页"除零（PanelPaging 的口径）
  assert.equal(view.pages, 1)
  assert.equal(view.pageNotice, '第 1/1 页')
  // 保留天数照服务端念，不是客户端写死的 90
  assert.equal(buildGachaHistory({ records: [], retentionDays: 30, serverNow: NOW }, NAMES).retentionText,
    '记录保留 30 天')
})

test('时间只用 serverNow 算：分级正确，且时钟不同步时不出现负数', () => {
  assert.equal(elapsedText(0), '刚刚')
  assert.equal(elapsedText(59_000), '刚刚')
  assert.equal(elapsedText(60_000), '1 分钟前')
  assert.equal(elapsedText(59 * 60_000), '59 分钟前')
  assert.equal(elapsedText(60 * 60_000), '1 小时前')
  assert.equal(elapsedText(23 * 3_600_000), '23 小时前')
  assert.equal(elapsedText(24 * 3_600_000), '1 天前')
  assert.equal(elapsedText(3 * 24 * 3_600_000), '3 天前')
  // 记录时刻晚于 serverNow（两端时钟不同步）：说「刚刚」，不许算出「-2 分钟前」
  assert.equal(elapsedText(-120_000), '刚刚')
  assert.equal(elapsedText(Number.NaN), '刚刚')
  for (const text of ['刚刚', '1 分钟前', '1 小时前', '1 天前']) {
    assert.ok(!text.includes('-'), `${text} 里不许出现负号`)
  }
})

test('名字缺失给「未知武将 / 未知卡池」，绝不把行 id 印给玩家', () => {
  const view = buildGachaHistory({
    records: [record({ poolId: 'pool_unknown', heroId: 'hero_unknown' })],
    retentionDays: 90, serverNow: NOW,
  }, NAMES)
  assert.equal(rowAt(view, 0).heroName, '未知武将')
  assert.equal(rowAt(view, 0).poolName, '未知卡池')
  const printed = view.rows.map((row) => `${row.heroName} ${row.poolName}`).join(' ')
  assert.ok(!printed.includes('hero_unknown') && !printed.includes('pool_unknown'),
    `屏上出现了裸 id：${printed}`)
})

test('名字命中时用随行下发的中文名，保底标记透传', () => {
  const view = buildGachaHistory({
    records: [
      record({ isPity: true, time: NOW - 5 * 60_000 }),
      record({ isPity: false, time: NOW - 3_600_000, heroId: 'hero_guanyu' }),
    ],
    retentionDays: 90, serverNow: NOW,
  }, NAMES)
  assert.equal(rowAt(view, 0).heroName, '关羽')
  assert.equal(rowAt(view, 0).poolName, '名将招募')
  assert.equal(rowAt(view, 0).timeText, '5 分钟前')
  assert.equal(rowAt(view, 0).pityText, '保底')
  // 非保底那一抽不许也挂上「保底」—— 全都标等于标记没有信息量
  assert.equal(rowAt(view, 1).pityText, null)
})

test('分页：每页 8 条、越界夹回最后一页、顺序照服务端（不重排）', () => {
  const records: GachaRecord[] = Array.from({ length: 50 }, (_value, index) =>
    record({ time: NOW - index * 60_000, heroId: index % 2 === 0 ? 'hero_guanyu' : 'hero_x' }))
  const first = buildGachaHistory({ records, retentionDays: 90, serverNow: NOW }, NAMES)
  assert.equal(first.total, 50)
  assert.equal(HISTORY_PAGE_SIZE, 8)
  assert.equal(first.pages, Math.ceil(50 / 8))
  assert.equal(first.rows.length, 8)
  assert.equal(rowAt(first, 0).timeText, '刚刚', '第一行必须是最新的那条（服务端倒序，客户端不重排）')
  assert.equal(rowAt(first, 1).timeText, '1 分钟前')

  const last = buildGachaHistory({ records, retentionDays: 90, serverNow: NOW }, NAMES, 99)
  assert.equal(last.page, last.pages - 1, '越界页码要夹回最后一页')
  assert.equal(last.rows.length, 50 - (last.pages - 1) * 8)
  assert.equal(last.pageNotice, `第 ${last.pages}/${last.pages} 页`)

  // 负数页码同样夹回第一页（翻页键灰了不吃触摸，但数据层不能依赖视图的自觉）
  assert.equal(buildGachaHistory({ records, retentionDays: 90, serverNow: NOW }, NAMES, -3).page, 0)
})

test('全源码不引本机时钟：记录页的时间只有一个来源（serverNow）', () => {
  const file = path.resolve('assets/scripts/game/gacha/GachaHistory.ts')
  const source = fs.readFileSync(file, 'utf8')
  assert.ok(!source.includes('Date.now'), 'GachaHistory.ts 里出现了 Date.now —— 记录时间必须相对 serverNow 算')
  assert.ok(!/new Date\(/.test(source), 'GachaHistory.ts 里出现了 new Date —— 同上')
})
