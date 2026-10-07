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

/**
 * 未成年付费提示的**面板半边**（#489 裁决后那句提示唯一的送达路径）。
 *
 * <p>为什么又要源码级判据：`GiftPopupView` 是 cc 组件，headless 没有 cc 运行时，
 * 而这一族的缺陷形状恰好是"流程把列带出来了、面板没画"—— 那屏上什么都不会报错，
 * 玩家看不见提示、机器也看不见（`GiftPayFlow.test.ts` 那三条只会绿）。
 * 反向同理：`view.minorNotice` 一旦被删掉，那三条用例仍然全绿。
 */
test('结果区必须真把 minorNotice 画出来（流程带出来了不等于屏上有）', () => {
  const source = readFileSync(VIEW, 'utf8')
  assert.ok(/lines\.push\(view\.minorNotice\)/.test(source),
    '结果区没有拼 view.minorNotice —— 服务端随下单回执给的额度提示到不了玩家眼前')
  // 空值那一行不许被造出来：不追加，而不是追加一句"没有额度限制"
  assert.ok(/if \(view\.minorNotice !== null/.test(source),
    '空提示必须整行不加（成年与"年龄未知"是两种态，客户端替它合并就造出第二真相）')
})

test('客户端不许自己造额度文案（提示只能来自那一列）', () => {
  // **先剥注释再扫字面量**：不剥的话，注释里举一个反例（"本月无额度限制"）就把门判红 ——
  // 那是"门被自己的写法判红"的假红（本仓栽过好几次：正向判据不剥注释必然误报）。
  const code = readFileSync(VIEW, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
  // 三种引号里的 \u0060 是反引号：写成字面反引号会被这套模板串自己的语法吞掉
  const quoted = new RegExp('[\\u0022\\u0027\\u0060][^\\u0022\\u0027\\u0060]*额度')
  assert.ok(!quoted.test(code),
    '视图代码里出现了带「额度」的字符串字面量 —— 额度文案的作者只能是服务端')
})
