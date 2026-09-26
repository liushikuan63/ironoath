/**
 * 职责：钉住礼包弹窗**不许把内部编号印给玩家**（#255/#268 同族；2026-09-21 复检在
 * 「建造完成 → 落成贺礼」弹窗上抓到的形态：界面写着「商品 gift_building_celebration」，
 * 而 `contract/config/pay_product.json` 那一行明明有中文名「落成贺礼」）。
 *
 * 依赖：node:test + node:assert + fs —— 与 `ArtFamilies.test.ts` 同一套"读源码/读生成物"的做法。
 *
 * <p><b>为什么是源码级判据而不是跑一遍视图</b>：`GiftPopupView` 是 cc 组件，headless 单测里
 * 没有 cc 运行时（铁律 2 只测引擎无关的逻辑）。而这条缺陷的形状极窄 —— 一处模板串 ——
 * 源码级断言既精确、又能失败：把 `${resp.productName ?? ''}` 改回 `${resp.productId}`，这里立刻红。
 *
 * <p><b>为什么还钉协议那一位</b>：视图能显示名字的前提是响应里真有 `productName`。
 * 只钉视图那一行的话，把字段从 schema 里删掉时这条用例照样绿 —— 那就成了假绿。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'

/** 单测的 cwd 是 `client/`（见 scripts/test-client.sh），所以从 cwd 往上找仓库根。 */
function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (existsSync(path.join(dir, 'contract', 'proto', 'pay.schema.json'))) {
      return dir
    }
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/proto/pay.schema.json，无法核对礼包弹窗文案')
}

const ROOT = repoRoot()
const VIEW = path.join(ROOT, 'client/assets/scripts/scene/GiftPopupView.ts')
const PROTOCOL = path.join(ROOT, 'client/assets/scripts/net/generated/PayProtocol.ts')
const CONFIG = path.join(ROOT, 'contract/config/pay_product.json')

test('礼包弹窗的副标题用服务端下发的显示名，绝不插值 productId', () => {
  const source = readFileSync(VIEW, 'utf8')
  assert.ok(!/\$\{resp\.productId\}/.test(source),
    '副标题里出现了 `${resp.productId}` —— 内部编号会直接印到玩家眼前')
  assert.ok(/resp\.productName/.test(source),
    '副标题必须取 resp.productName（服务端下发的显示名）')
})

test('协议里确有 productName；配置表每一档商品也都有中文名可下发', () => {
  const protocol = readFileSync(PROTOCOL, 'utf8')
  assert.ok(/productName\s*:\s*string \| null/.test(protocol),
    'GiftPopupResp 必须带 productName（string | null）—— 缺了它客户端只能印 id')

  const rows = JSON.parse(readFileSync(CONFIG, 'utf8')).rows as Array<{ id: string, name?: string }>
  const nameless = rows.filter((row) => typeof row.name !== 'string' || row.name.length === 0
    || row.name === row.id)
  assert.deepEqual(nameless.map((row) => row.id), [],
    'pay_product 有行缺中文名（或名字就是 id）：服务端只能把 id 下发出去')
})
