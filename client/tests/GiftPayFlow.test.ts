/**
 * 职责：礼包购买状态机的每条分支（B19 S3-iv 的"支付 + 轮询"半段）。
 * 依赖：注入的假三件套，不碰真网络、不 import 'cc'。
 *
 * <p>三条合规口径各有用例钉住：**retryQueued 不许说"已到账"**、**轮询超时不许报失败**、
 * **浏览器里不是死按钮**。这三条写错都要等真实投诉才会被发现，所以让它们在测试里先红。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { GiftPayFlow } from '../assets/scripts/game/pay/GiftPayFlow'
import type { PayFlowDeps, PaymentOutcome } from '../assets/scripts/game/pay/GiftPayFlow'
import type { NetOutcome } from '../assets/scripts/net/NetModule'
import type { CreateOrderResp, OrderStatusResp } from '../assets/scripts/net/generated/PayProtocol'

const PAY_PARAMS = {
  mode: 'game', offerId: 'offer-1', buyQuantity: '600', env: '1',
  currencyType: 'CNY', signature: null,
}

function okOutcome<T>(data: T): NetOutcome<T> {
  return { kind: 'ok', data, traceId: 't', serverNow: 1 }
}

interface Harness {
  flow: GiftPayFlow
  orderStatusCalls: number[]
  tracked: Array<{ name: string, params: Record<string, string> }>
  delays: number[]
}

function harness(options: {
  createOrder?: NetOutcome<CreateOrderResp>
  statuses?: Array<NetOutcome<OrderStatusResp>>
  payment?: PaymentOutcome
  maxPolls?: number
} = {}): Harness {
  const statuses = options.statuses ?? []
  const orderStatusCalls: number[] = []
  const tracked: Array<{ name: string, params: Record<string, string> }> = []
  const delays: number[] = []
  let statusIndex = 0

  const deps: PayFlowDeps = {
    createOrder: async () => options.createOrder ?? okOutcome<CreateOrderResp>({
      // **minorNotice 必须显式给 null**：生成类型把它列成必填（契约里 required 不含它，
      // 但 TS 侧按非可选生成），而「成年 / 年龄未知」这一路的真值就是 null ——
      // 夹具不写它会在类型检查期报缺字段，那是第一道能抓住的信号。
      orderId: 'order-1', minorNotice: null, payParams: PAY_PARAMS,
    }),
    orderStatus: async () => {
      orderStatusCalls.push(statusIndex)
      const step = statuses[Math.min(statusIndex, statuses.length - 1)]
      statusIndex += 1
      assert.ok(step !== undefined, 'FakeStatus 未配置 script')
      return step
    },
    invokePayment: async () => options.payment ?? 'ok',
    now: () => 1_000,
    delay: async (ms: number) => {
      delays.push(ms)
    },
    track: (name, params) => tracked.push({ name, params }),
  }
  return {
    flow: new GiftPayFlow(deps, { maxPolls: options.maxPolls ?? 5, pollIntervalMs: 1_000 }),
    orderStatusCalls,
    tracked,
    delays,
  }
}

function success(rewards: OrderStatusResp['rewards'] = []): NetOutcome<OrderStatusResp> {
  return okOutcome<OrderStatusResp>({ status: 'SUCCESS', rewards, retryQueued: null })
}

test('成功：下单 → 拉起 → 轮询到 SUCCESS 且带奖励 ⇒ 已到账，奖励原样来自服务端', async () => {
  const h = harness({
    statuses: [success([{ type: 'RESOURCE', id: 'GOLD', count: 180 }])],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'delivered')
  assert.equal(view.title, '已到账')
  assert.equal(view.rewards.length, 1)
  assert.equal(view.rewards[0]?.count, 180)
  assert.deepEqual(h.tracked.map(t => t.name), ['pay_click'])
  assert.equal(h.tracked[0]?.params.productId, 'gift_stuck_supply')
})

test('钱到了货没到：SUCCESS 但 rewards 为空 ⇒ 发货处理中；标题里不许出现「已到账」', async () => {
  const h = harness({ statuses: [success([])] })

  const view = await h.flow.buy('gift_defeat_relief')

  assert.equal(view.phase, 'processing')
  assert.equal(view.title, '发货处理中')
  assert.equal(view.showSupport, true, '这一支必须给客服入口')
  assert.ok(!JSON.stringify(view).includes('已到账'),
    'retryQueued 场景说"已到账"会引发退款投诉（B19 §四 禁止项）')
})

test('retryQueued=true 同样走「发货处理中」', async () => {
  const h = harness({
    statuses: [okOutcome<OrderStatusResp>({ status: 'SUCCESS', rewards: [], retryQueued: true })],
  })

  assert.equal((await h.flow.buy('gift_stuck_supply')).phase, 'processing')
})

test('支付取消 ⇒ cancelled，且一次都不该去轮询', async () => {
  const h = harness({ payment: 'cancelled' })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'cancelled')
  assert.ok(view.detail?.includes('没有扣款') === true, `取消要说清没扣钱，实际：${view.detail}`)
  assert.equal(h.orderStatusCalls.length, 0, '用户没付钱，查单没有意义')
})

test('浏览器/编辑器（拉不起支付）⇒ 明确说清是环境限制，不是死按钮', async () => {
  const h = harness({ payment: 'unsupported' })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'failed')
  assert.ok((view.detail ?? '').includes('微信小游戏'), `要指向正确环境，实际：${view.detail}`)
})

test('下单被业务拒绝 ⇒ 原样用服务端的提示（15011 今天买过了 / 15012 报价过期都走这条）', async () => {
  const h = harness({
    createOrder: {
      kind: 'biz', code: 15011, msg: '这个礼包今天已经买过了：同类礼包每天一次，明天或下一次触发时再来',
      detail: '今日已购=1 上限=1', traceId: 't',
    },
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'failed')
  assert.ok(view.title.includes('今天已经买过了'), '服务端的 msg 就是给玩家写的，别再自己编一句')
  assert.equal(view.detail, '今日已购=1 上限=1', 'detail 给客服/排查用，原样带出去')
})

test('轮询一直 PENDING ⇒ 用完次数后是「发货处理中」，绝不报失败（那一刻钱可能已扣）', async () => {
  const pending = okOutcome<OrderStatusResp>({ status: 'PENDING', rewards: [], retryQueued: null })
  const h = harness({ statuses: [pending], maxPolls: 3 })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'processing')
  assert.equal(h.orderStatusCalls.length, 3)
  assert.deepEqual(h.delays, [1_000, 1_000], '第一次不等，之后每次轮询前等一个间隔')
})

test('弱网轮询：前两次查不到不下结论，第三次拿到 SUCCESS ⇒ 已到账', async () => {
  const h = harness({
    statuses: [
      { kind: 'network', message: 'fetch failed', queued: false, requestId: null },
      { kind: 'network', message: 'fetch failed', queued: false, requestId: null },
      success([{ type: 'RESOURCE', id: 'GOLD', count: 180 }]),
    ],
  })

  assert.equal((await h.flow.buy('gift_stuck_supply')).phase, 'delivered')
})

test('拉起支付本身失败（非取消）⇒ 不轮询、报失败：那一刻一分钱都没扣，说"处理中"是假话', async () => {
  const h = harness({ payment: 'failed' })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'failed')
  assert.ok((view.detail ?? '').includes('没有扣款'), `要说清没扣钱，实际：${view.detail}`)
  assert.equal(h.orderStatusCalls.length, 0, '没付款就没有可查的订单状态')
})

/**
 * #489 之后那句"未成年付费额度还剩多少"只有一条送达路径：`CreateOrderResp.minorNotice`。
 * 错误码 `PAY_MINOR_LIMIT(15002)` 已不再抛出，动态文案放 `Result.detail` 在 prod 会被置 null，
 * 所以这一列一旦被流程丢掉，合规提示就整条落空——而屏上什么都不会报错。
 * 三条用例分别钉「原样搬运」「空值不许自造话」「四种结局都不丢」。
 */
const MINOR_NOTICE = '本次可正常下单。本月未成年消费额度还剩 30 元。'

function orderWithNotice(minorNotice: string | null): NetOutcome<CreateOrderResp> {
  return okOutcome<CreateOrderResp>({ orderId: 'order-1', minorNotice, payParams: PAY_PARAMS })
}

test('未成年提示：服务端给了就逐字带出来（客户端不加工、不改一个标点）', async () => {
  const h = harness({
    createOrder: orderWithNotice(MINOR_NOTICE),
    statuses: [success([{ type: 'RESOURCE', id: 'GOLD', count: 180 }])],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'delivered')
  // 严格相等而不是 includes：includes 放过"顺手把句号改成感叹号/把金额重排"的改写
  assert.equal(view.minorNotice, MINOR_NOTICE, '这一列必须原样搬运，客户端不是文案的作者')
})

test('未成年提示：服务端说本次无需提示（null）⇒ 视图也是 null，客户端不许替它造一句', async () => {
  const h = harness({
    createOrder: orderWithNotice(null),
    statuses: [success([{ type: 'RESOURCE', id: 'GOLD', count: 180 }])],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.minorNotice, null)
  // 「成年」与「年龄未知」在服务端是两种态，客户端合并成一句"没有额度限制"就是第二个真相
  assert.ok(!JSON.stringify(view).includes('额度'), `不许出现客户端自造的额度措辞，实际：${JSON.stringify(view)}`)
})

test('未成年提示：四种结局都带着它（它与支付成败无关——超限也照常下单）', async () => {
  const delivered = await harness({
    createOrder: orderWithNotice(MINOR_NOTICE),
    statuses: [success([{ type: 'RESOURCE', id: 'GOLD', count: 1 }])],
  }).flow.buy('gift_stuck_supply')
  const processingView = await harness({
    createOrder: orderWithNotice(MINOR_NOTICE),
    statuses: [success([])],
  }).flow.buy('gift_stuck_supply')
  const cancelled = await harness({
    createOrder: orderWithNotice(MINOR_NOTICE),
    payment: 'cancelled',
  }).flow.buy('gift_stuck_supply')
  const payFailed = await harness({
    createOrder: orderWithNotice(MINOR_NOTICE),
    payment: 'failed',
  }).flow.buy('gift_stuck_supply')

  for (const [phase, view] of [['delivered', delivered], ['processing', processingView],
    ['cancelled', cancelled], ['failed', payFailed]] as const) {
    assert.equal(view.phase, phase, `夹具没造出这一支：${view.phase}`)
    assert.equal(view.minorNotice, MINOR_NOTICE, `${phase} 这一支把提示弄丢了`)
  }
})
