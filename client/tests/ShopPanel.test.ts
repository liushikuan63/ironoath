/**
 * 职责：商店面板的纯逻辑用例（B24 S-b，验收 1 的客户端半边）。
 * 依赖：node:test + game/shop/ShopPanel（不碰 cc）。
 *
 * <p><b>这些用例盯的是四处最容易做假的地方</b>：① 可兑换与否**只信服务端那一位**
 * （客户端不重新比等级/限购/余额）；② 不能买时**必须给原因**（一个没有解释的灰按钮会被当成坏了）；
 * ③ 限购文案要带**周期**（玩家最想知道的是"什么时候能再买"）；④ 余额为 null 时**不显示余额**
 * （显示假的 0 会让玩家以为"我有 0 个赛季币"，而真实情况是"这一页还没有出处"）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buyBodyOf, buildShopPanel, buyResultText, canBuy, currencyLabel, SHOP_TABS, shopRowStateText,
} from '../assets/scripts/game/shop/ShopPanel'
import type { ShopListResp, ShopRowView } from '../assets/scripts/net/generated/ShopProtocol'

const NOW = 1_700_000_000_000

function row(overrides: Partial<ShopRowView> = {}): ShopRowView {
  return {
    rowId: 'shop_speedup_build_1h', itemId: 'item_speedup_build_1h', name: '建造加速 1 小时',
    currency: 'GOLD', price: 300, refreshType: 'NONE', limitCount: 20, used: 3, remaining: 17,
    requireMainLevel: 0, purchasable: true, lockReason: null, ...overrides,
  }
}

function resp(overrides: Partial<ShopListResp> = {}): ShopListResp {
  return { currency: 'GOLD', open: true, notice: null, rows: [row()], balance: 1200, serverNow: NOW,
    ...overrides }
}

test('四个币种页签齐、顺序固定，且币种名是玩家语言（不是枚举名）', () => {
  assert.deepEqual(SHOP_TABS.map(t => t.currency),
    ['GOLD', 'ALLIANCE_COIN', 'SQUAD_COIN', 'SEASON_COIN'])
  assert.deepEqual(SHOP_TABS.map(t => t.label), ['金币', '贡献', '小队币', '赛季币'])
  assert.equal(currencyLabel('SEASON_COIN'), '赛季币')
  assert.equal(currencyLabel('MYSTERY' as never), 'MYSTERY', '表里没有的取值退回枚举名，不显示空白')
})

test('价签与余额都带币种名：余额是服务端给的数，客户端不做算术', () => {
  const view = buildShopPanel(resp({ rows: [row({ currency: 'SEASON_COIN', price: 100 })],
    currency: 'SEASON_COIN', balance: 200 }), 'SEASON_COIN')
  assert.equal(view.balanceText, '200 赛季币')
  assert.equal(view.rows[0]?.priceText, '100 赛季币')
})

test('余额为 null 时一律不显示余额（那是"这一页还没有出处"，不是"我有 0 个"）', () => {
  const view = buildShopPanel(resp({ balance: null }), 'GOLD')
  assert.equal(view.balanceText, null)
})

test('限购文案带周期：永久 / 今日 / 本周 / 本赛季各一句', () => {
  const pick = (refreshType: ShopRowView['refreshType']) =>
    buildShopPanel(resp({ rows: [row({ refreshType, limitCount: 2, used: 1 })] }), 'GOLD')
      .rows[0]?.limitText
  assert.equal(pick('NONE'), '永久限 2，已买 1')
  assert.equal(pick('DAILY'), '今日限 2，已买 1')
  assert.equal(pick('WEEKLY'), '本周限 2，已买 1')
  assert.equal(pick('SEASON'), '本赛季限 2，已买 1')
})

test('可兑换与否只信服务端那一位；不能买时必须给出人话原因', () => {
  const locked = buildShopPanel(resp({ rows: [
    row({ rowId: 'r-level', purchasable: false, lockReason: '主城 5 级解锁', remaining: 17 }),
  ] }), 'GOLD')
  assert.equal(canBuy(locked.rows[0]!), false, '服务端说不能买就是不能买，不因为余额够就放行')
  assert.equal(shopRowStateText(locked.rows[0]!), '主城 5 级解锁')

  const canBuyRow = buildShopPanel(resp(), 'GOLD').rows[0]!
  assert.equal(canBuy(canBuyRow), true)
  assert.equal(shopRowStateText(canBuyRow), '可兑换')

  // 服务端漏了原因也要有个说法：一个没有解释的灰按钮会被当成坏了
  const noReason = buildShopPanel(resp({ rows: [row({ purchasable: false, lockReason: null })] }),
    'GOLD').rows[0]!
  assert.equal(shopRowStateText(noReason), '现在还不能兑换')
})

test('不能买时不发请求：buyBodyOf 返回 null（先把原因说给玩家听）', () => {
  const view = buildShopPanel(resp({ rows: [
    row({ rowId: 'r-locked', purchasable: false, lockReason: '本周限购已用完' }),
    row({ rowId: 'r-ok' }),
  ] }), 'GOLD')
  assert.equal(buyBodyOf(view, 'r-locked'), null)
  assert.deepEqual(buyBodyOf(view, 'r-ok'), { currency: 'GOLD', rowId: 'r-ok', count: 1 })
  assert.equal(buyBodyOf(view, '不存在的一行'), null, '不认识的 rowId 不发请求')
})

test('那一页没开时：仍然列出货架，但带一句为什么（"还没有"与"不存在"是两句话）', () => {
  const view = buildShopPanel(resp({ open: false, notice: '赛季币这一页还没有开放', rows: [row({ purchasable: false, lockReason: null })] }), 'SEASON_COIN')
  assert.equal(view.open, false)
  assert.equal(view.noticeText, '赛季币这一页还没有开放')
  assert.equal(view.rows.length, 1, '货架照给：让玩家看见以后能换什么')
  assert.equal(buyBodyOf(view, 'shop_speedup_build_1h'), null, '没开就不发请求')
  // 服务端没给 notice 时也要有一句，而不是一片空白
  const fallback = buildShopPanel(resp({ open: false, notice: null }), 'GOLD')
  assert.equal(fallback.noticeText, '这一页暂时还不能兑换')
})

test('兑换回执那句用的是服务端给的数字（本地那份表可能已经过期）', () => {
  assert.equal(buyResultText('建造加速 1 小时', 300), '已兑换 建造加速 1 小时，花费 300')
})
