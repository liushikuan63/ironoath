/** Executes the real scene render/events with node stubs; browser/native acceptance is separate. */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as paging from '../assets/scripts/game/ui/PanelPaging'
import * as shop from '../assets/scripts/game/shop/ShopPanel'
import type { MonthlyCardRow } from '../assets/scripts/game/shop/MonthlyCardPanel'
import type { MonthlyShopView, ShopView } from '../assets/scripts/game/session/AppRoot'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0; anchorX = .5; anchorY = .5
  setAnchorPoint(x: number, y: number): void { this.anchorX = x; this.anchorY = y }
  setContentSize(size: Size | number, height?: number): void { this.width = typeof size === 'number' ? size : size.width; this.height = typeof size === 'number' ? height! : size.height }
}
class Graphics {
  fillColor: unknown; strokeColor: unknown; lineWidth = 0
  clear(): void {} rect(): void {} roundRect(): void {} fill(): void {} stroke(): void {}
}
class Label {
  static Overflow = { NONE: 0, SHRINK: 2 }; static HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 }; static VerticalAlign = { CENTER: 1 }
  node!: Node; string = ''; color: unknown; fontSize = 0; lineHeight = 40; horizontalAlign = 0; verticalAlign = 0
  enableWrapText = true; overflow = 0
}
class Node {
  active = true; layer = 1; parent: Node | null = null; position = new Vec3(); readonly children: Node[] = []
  private readonly components = new Map<unknown, unknown>(); private readonly events = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(node: Node): void { node.parent = this; this.children.push(node) }
  addComponent<T extends object>(type: new () => T): T { const value = new type(); Object.assign(value, { node: this }); this.components.set(type, value); return value }
  getComponent<T>(type: new () => T): T | null { return this.components.get(type) as T | undefined ?? null }
  setPosition(position: Vec3): void { this.position = position }
  on(event: string, callback: () => void): void { this.events.set(event, callback) }
  emit(event: string): void { this.events.get(event)?.() }
}
class Component { node = new Node('shop') }
class Color { constructor(..._values: number[]) {} }
interface Harness {
  node: Node; onLoad(): void; onDestroy(): void; attach(data: ShopView): void; attachMonthlyCards(data: MonthlyShopView): void
  onMonthlyTab: (() => void) | null; onTab: ((currency: string) => void) | null
  onCardClaim: ((productId: string | null) => void) | null; onCardPurchase: ((productId: string) => void) | null
}
function setup(width: number, height: number): Harness {
  const exportsFor = (name: string, values: Record<string, unknown>): Record<string, unknown> => {
    const source = readFileSync(path.join(process.cwd(), 'assets/scripts/scene', name + '.ts'), 'utf8')
    const ast = ts.createSourceFile(name + '.ts', source, ts.ScriptTarget.ES2020, true)
    const stripped = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast, ast.statements.filter(s => !ts.isImportDeclaration(s))))
    const code = ts.transpileModule(stripped, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, experimentalDecorators: true } }).outputText
    const exports: Record<string, unknown> = {}
    runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass }, Node, Component, Color, Graphics, Label, UITransform, Vec3, Size, ...values })
    return exports
  }
  const font = exportsFor('UiFont', {})
  const Scene = exportsFor('ShopPanelView', { ...font, ...paging, ...shop, view: { getVisibleSize: () => ({ width, height }) } }).ShopPanelView as new () => Harness
  const result = new Scene(); result.onLoad(); return result
}
function child(node: Node, name: string): Node { const found = node.children.find(n => n.name === name); assert.ok(found, 'Missing ' + name); return found }
function text(node: Node, name: string): string { return child(node, name).getComponent(Label)!.string }
function row(overrides: Partial<MonthlyCardRow> = {}): MonthlyCardRow {
  return { productId: 'monthly_material_card', name: '材料月卡', priceText: '18元 / 30天',
    offerText: '每日：一小时建造令 ×1、木材包 ×1；免广告播放，不增加每日次数；四种手动教学后可提前一键收割',
    statusText: '可领取1天日包；免广告生效；提前收割生效，仍需完成四种手动教学',
    expiryText: '到期：2026-11-12 16:00（北京时间）', refreshText: '刷新：2026-10-14 04:00（北京时间）',
    claimable: true, purchasable: true, rewardNames: {}, ...overrides }
}
function data(rows: readonly MonthlyCardRow[] = [row(), row({ productId: 'monthly_gold_card', name: '金币月卡', priceText: '30元 / 30天', offerText: '购买赠300金币；每日：金币 ×90', statusText: '可领取1天日包' })], noticeText: string | null = null, notice: string | null = null): MonthlyShopView {
  return { rows, noticeText, notice, serverNow: 1234 }
}
function cards(scene: Harness): Node[] { return child(scene.node, 'MonthlyCards').children.filter(n => n.name.startsWith('MonthlyCardRow_') && n.active) }
function bounds(node: Node): { left: number; right: number; top: number; bottom: number } {
  let x = 0, y = 0, current: Node | null = node
  while (current) { x += current.position.x; y += current.position.y; current = current.parent }
  const t = node.getComponent(UITransform)!
  return { left: x - t.width * t.anchorX, right: x + t.width * (1 - t.anchorX), bottom: y - t.height * t.anchorY, top: y + t.height * (1 - t.anchorY) }
}
function noOverlap(a: Node, b: Node): void {
  const x = bounds(a), y = bounds(b)
  assert.ok(x.right <= y.left || y.right <= x.left || x.top <= y.bottom || y.top <= x.bottom, `${a.name} overlaps ${b.name}`)
}

test('月卡页签可达，领取和购买分别回抛当前服务端商品', () => {
  const scene = setup(960, 540), selected: string[] = []
  scene.onMonthlyTab = () => selected.push('monthly'); scene.onCardClaim = id => selected.push('claim:' + id); scene.onCardPurchase = id => selected.push('buy:' + id)
  child(scene.node, 'Tab_MonthlyCards').emit('touch-start'); assert.deepEqual(selected, ['monthly'])
  scene.attachMonthlyCards(data())
  const first = cards(scene)[0]!
  child(first, 'ClaimButton').emit('touch-start'); child(first, 'PurchaseButton').emit('touch-start')
  assert.deepEqual(selected, ['monthly', 'claim:monthly_material_card', 'buy:monthly_material_card'])
  child(scene.node, 'NextPageButton').emit('touch-start')
  child(cards(scene)[0]!, 'ClaimButton').emit('touch-start')
  assert.equal(selected.at(-1), 'claim:monthly_gold_card')
})

for (const [width, height] of [[960, 443.6018957345971], [960, 540], [1440, 900]]) {
  test(`月卡${width}×${height}：两新卡与旧卡全部可达，文字/操作/翻页互不遮挡`, () => {
    const scene = setup(width!, height!), visited = new Set<string>()
    scene.attachMonthlyCards(data([row(), row({ productId: 'monthly_gold_card', name: '金币月卡' }), row({ productId: null, name: '已购旧月卡', purchasable: false })]))
    for (let page = 0; page < 4; page++) {
      for (const card of cards(scene)) {
        visited.add(text(card, 'Name'))
        const box = bounds(card)
        assert.ok(box.left >= -width! / 2 && box.right <= width! / 2 && box.bottom >= -height! / 2 + 68 && box.top <= height! / 2,
          `card must stay above navigation and inside ${width}×${height}: ${JSON.stringify(box)}`)
        for (const part of card.children) {
          const own = bounds(part)
          assert.ok(own.left >= box.left && own.right <= box.right && own.bottom >= box.bottom && own.top <= box.top, part.name + ' escapes card')
          for (const other of card.children) if (part !== other) noOverlap(part, other)
          const label = part.getComponent(Label)
          if (label) {
            assert.equal(label.lineHeight, label.fontSize + 6)
            assert.ok(label.fontSize >= 13, part.name + ' must keep a readable base size')
          }
          if (part.name.endsWith('Button')) assert.ok(part.getComponent(UITransform)!.height >= 32)
        }
      }
      const next = child(scene.node, 'NextPageButton')
      if (next.active) {
        assert.ok(bounds(next).bottom >= -height! / 2 + 68)
        for (const card of cards(scene)) noOverlap(card, next)
        next.emit('touch-start')
      }
    }
    assert.deepEqual([...visited].sort(), ['已购旧月卡', '材料月卡', '金币月卡'].sort())
  })
}

test('443.6高月卡长支付回执有两行，表头/说明/回执/页签/卡片各在独立槽，切回货架恢复旧头部', () => {
  const scene = setup(960, 443.6018957345971)
  const notice = '当前环境不支持支付，请在微信小游戏内重新进入后尝试。未成年人消费额度由平台校验，请遵守游戏内消费限制。'
  scene.attachMonthlyCards(data(undefined, null, notice))
  const header = child(scene.node, 'Header'), policy = child(scene.node, 'Balance'), outcome = child(scene.node, 'Notice'), tab = child(scene.node, 'Tab_MonthlyCards')
  assert.equal(text(scene.node, 'Notice'), notice)
  const label = outcome.getComponent(Label)!
  assert.ok(label.enableWrapText)
  assert.ok(outcome.getComponent(UITransform)!.height >= label.lineHeight * 2, 'receipt needs two full-size lines')
  assert.ok(label.fontSize >= 14)
  for (const a of [header, policy, outcome, tab, ...cards(scene)]) {
    for (const b of [header, policy, outcome, tab, ...cards(scene)]) if (a !== b) noOverlap(a, b)
  }
  scene.attach({ ...shop.buildShopPanel({ currency: 'GOLD', balance: 500, open: true, notice: null, rows: [], serverNow: 1 }, 'GOLD'), notice: null })
  assert.equal(child(scene.node, 'Notice').getComponent(UITransform)!.height, 27)
  assert.equal(child(scene.node, 'Notice').position.y, 443.6018957345971 / 2 - 16 - 76)
  assert.equal(tab.position.y, 443.6018957345971 / 2 - 16 - 112)
})

test('旧卡沿原空商品领取，停止销售和已结清按钮不会发送购买或领取', () => {
  const scene = setup(960, 540), claims: Array<string | null> = [], purchases: string[] = []
  scene.onCardClaim = id => claims.push(id); scene.onCardPurchase = id => purchases.push(id)
  scene.attachMonthlyCards(data([row({ productId: null, name: '已购旧月卡', priceText: null, purchasable: false })]))
  let card = cards(scene)[0]!
  assert.equal(text(card, 'Price'), '已购权益保留')
  child(card, 'ClaimButton').emit('touch-start'); child(card, 'PurchaseButton').emit('touch-start')
  assert.deepEqual(claims, [null]); assert.deepEqual(purchases, [])
  scene.attachMonthlyCards(data([row({ claimable: false, purchasable: false })])); card = cards(scene)[0]!
  assert.equal(text(child(card, 'ClaimButton'), 'Caption'), '暂无可领')
  child(card, 'ClaimButton').emit('touch-start'); assert.deepEqual(claims, [null])
})

test('服务端回执和补领边界可见；同页领取刷新不跳回首卡，空列表清掉旧按钮', () => {
  const scene = setup(960, 540); scene.attachMonthlyCards(data()); child(scene.node, 'NextPageButton').emit('touch-start')
  scene.attachMonthlyCards(data(undefined, null, '已结算1天日包，到账：金币 ×90'))
  assert.equal(text(cards(scene)[0]!, 'Name'), '金币月卡')
  assert.equal(text(scene.node, 'Notice'), '已结算1天日包，到账：金币 ×90')
  scene.attachMonthlyCards(data(data().rows.map(item => ({ ...item, claimable: false, statusText: '当前日包已结清' })), '日包状态已刷新', '已结算1天日包，到账：金币 ×90'))
  assert.equal(text(scene.node, 'Notice'), '已结算1天日包，到账：金币 ×90', 'orchestrator receipt must remain visible after authoritative status refresh')
  assert.match(text(scene.node, 'Balance'), /手动领取.*有效期内可补领.*当前可领天数/)
  scene.attachMonthlyCards(data([], '当前服务暂未提供月卡列表'))
  assert.equal(cards(scene).length, 0); assert.equal(child(scene.node, 'NextPageButton').active, false)
  assert.equal(text(scene.node, 'Notice'), '当前服务暂未提供月卡列表')
})

test('月卡切回货架恢复原行、货币回调和页码；再回月卡从首卡开始', () => {
  const scene = setup(960, 540), selected: string[] = []; scene.onTab = currency => selected.push(currency)
  scene.attachMonthlyCards(data()); child(scene.node, 'NextPageButton').emit('touch-start')
  child(scene.node, 'Tab_GOLD').emit('touch-start'); assert.deepEqual(selected, ['GOLD'])
  scene.attach({ ...shop.buildShopPanel({ currency: 'GOLD', balance: 500, open: true, notice: null, rows: [], serverNow: 1 }, 'GOLD'), notice: null })
  assert.equal(child(scene.node, 'MonthlyCards').active, false); assert.equal(text(scene.node, 'Balance'), '500 金币')
  scene.attachMonthlyCards(data()); assert.equal(text(cards(scene)[0]!, 'Name'), '材料月卡')
  scene.onDestroy(); assert.equal(scene.onMonthlyTab, null); assert.equal(scene.onCardClaim, null); assert.equal(scene.onCardPurchase, null)
})
