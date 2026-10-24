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
  currencyType: 'CNY', signature: null, outTradeNo: '0123456789abcdef0123456789abcdef',
}

function okOutcome<T>(data: T): NetOutcome<T> {
  return { kind: 'ok', data, traceId: 't', serverNow: 1 }
}

interface Harness {
  flow: GiftPayFlow
  createOrderCalls: Array<{ productId: string, requestId: string | undefined }>
  paymentCalls: number[]
  orderStatusCalls: number[]
  tracked: Array<{ name: string, params: Record<string, string> }>
  delays: number[]
}

function harness(options: {
  createOrder?: NetOutcome<CreateOrderResp>
  createOrders?: Array<NetOutcome<CreateOrderResp>>
  statuses?: Array<NetOutcome<OrderStatusResp>>
  payment?: PaymentOutcome
  maxPolls?: number
} = {}): Harness {
  const statuses = options.statuses ?? []
  const createOrderCalls: Array<{ productId: string, requestId: string | undefined }> = []
  const paymentCalls: number[] = []
  const orderStatusCalls: number[] = []
  const tracked: Array<{ name: string, params: Record<string, string> }> = []
  const delays: number[] = []
  let statusIndex = 0
  let orderIndex = 0

  const deps: PayFlowDeps = {
    createOrder: async (productId, requestId) => {
      createOrderCalls.push({ productId, requestId })
      const defaultOrder = okOutcome<CreateOrderResp>({
      // **minorNotice 必须显式给 null**：生成类型把它列成必填（契约里 required 不含它，
      // 但 TS 侧按非可选生成），而「成年 / 年龄未知」这一路的真值就是 null ——
      // 夹具不写它会在类型检查期报缺字段，那是第一道能抓住的信号。
      orderId: 'order-1', minorNotice: null, payParams: PAY_PARAMS,
      })
      const script = options.createOrders ?? [options.createOrder ?? defaultOrder]
      const step = script[Math.min(orderIndex, script.length - 1)]
      orderIndex += 1
      assert.ok(step !== undefined, 'FakeOrder 未配置 script')
      return step
    },
    orderStatus: async () => {
      orderStatusCalls.push(statusIndex)
      const step = statuses[Math.min(statusIndex, statuses.length - 1)]
      statusIndex += 1
      assert.ok(step !== undefined, 'FakeStatus 未配置 script')
      return step
    },
    invokePayment: async () => {
      paymentCalls.push(1)
      return options.payment ?? 'ok'
    },
    now: () => 1_000,
    delay: async (ms: number) => {
      delays.push(ms)
    },
    track: (name, params) => tracked.push({ name, params }),
  }
  return {
    flow: new GiftPayFlow(deps, { maxPolls: options.maxPolls ?? 5, pollIntervalMs: 1_000 }),
    createOrderCalls,
    paymentCalls,
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
  assert.match(view.detail ?? '', /款项已收到/, '只有服务端 SUCCESS 才能说已收款')
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

test('支付桥报告取消时不凭客户端回调断言未扣款', async () => {
  const h = harness({
    payment: 'cancelled',
    statuses: [okOutcome<OrderStatusResp>({ status: 'PENDING', rewards: [], retryQueued: null })],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.orderStatusCalls.length, 1, '取消回调仍要查本人订单，不能当成服务端取消凭据')
  assert.equal(view.phase, 'cancelled')
  assert.doesNotMatch(view.detail ?? '', /没有扣款|已到账|订单已取消/,
    '取消回调也不代替服务端订单状态')
  assert.match(view.detail ?? '', /客服/, '有扣款争议时仍能找到处理入口')
  assert.equal(view.showSupport, true)
})

test('支付桥报告取消但本人订单已到账时，以服务端奖励为准', async () => {
  const h = harness({
    payment: 'cancelled',
    statuses: [success([{ type: 'RESOURCE', id: 'GOLD', count: 180 }])],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.orderStatusCalls.length, 1)
  assert.equal(view.phase, 'delivered')
  assert.equal(view.rewards[0]?.count, 180)
})

test('取消回调后的查单弱网仍给客服入口，不把未知当作已取消订单', async () => {
  const h = harness({
    payment: 'cancelled',
    statuses: [{ kind: 'network', message: 'fetch failed', queued: false, requestId: null }],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.orderStatusCalls.length, 1)
  assert.equal(view.phase, 'cancelled')
  assert.equal(view.showSupport, true)
  assert.match(view.detail ?? '', /订单状态|待确认/)
})

test('浏览器/编辑器（拉不起支付）⇒ 明确说清是环境限制，不是死按钮', async () => {
  const h = harness({ payment: 'unsupported' })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'failed')
  assert.ok((view.detail ?? '').includes('微信小游戏'), `要指向正确环境，实际：${view.detail}`)
})

test('下单回执丢失时不声称请求没有发出；本次没有拉起支付', async () => {
  const h = harness({
    createOrder: { kind: 'network', message: 'response lost', queued: false, requestId: 'req-1' },
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'failed')
  assert.match(view.detail ?? '', /下单结果|下单回执/)
  assert.match(view.detail ?? '', /支付未拉起|未拉起支付/)
  assert.doesNotMatch(view.detail ?? '', /没有发出去|没有扣款/)
  assert.equal(h.orderStatusCalls.length, 0, '没有 orderId 不能调用按订单号查询')
})

test('下单回执失联且有请求号时，只用原号有界重放；拿到原单后才拉起一次支付', async () => {
  const h = harness({
    createOrders: [
      { kind: 'network', message: 'response lost', queued: false, requestId: 'req-recover-1' },
      okOutcome<CreateOrderResp>({ orderId: 'order-original', minorNotice: null, payParams: PAY_PARAMS }),
    ],
    statuses: [success([{ type: 'RESOURCE', id: 'GOLD', count: 180 }])],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.deepEqual(h.createOrderCalls, [
    { productId: 'gift_stuck_supply', requestId: undefined },
    { productId: 'gift_stuck_supply', requestId: 'req-recover-1' },
  ])
  assert.deepEqual(h.delays, [1_000])
  assert.equal(h.paymentCalls.length, 1)
  assert.equal(view.phase, 'delivered')
})

test('网络态缺请求号或已入队时不猜新键重投，也不拉起支付', async () => {
  for (const outcome of [
    { kind: 'network', message: 'unknown', queued: false, requestId: null },
    { kind: 'network', message: 'queued', queued: true, requestId: 'req-queued' },
  ] as const) {
    const h = harness({ createOrder: outcome })
    const view = await h.flow.buy('gift_stuck_supply')
    assert.equal(h.createOrderCalls.length, 1)
    assert.equal(h.paymentCalls.length, 0)
    assert.equal(view.phase, 'failed')
    assert.equal(view.showSupport, true)
  }
})

test('旧后端同键重投回重复请求时仍报未知并给客服，不把它说成已下单或已扣款', async () => {
  const h = harness({
    createOrders: [
      { kind: 'network', message: 'response lost', queued: false, requestId: 'req-old-server' },
      { kind: 'biz', code: 1002, msg: '请求重复提交', detail: null, traceId: 't' },
    ],
  })
  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.createOrderCalls[1]?.requestId, 'req-old-server')
  assert.equal(h.paymentCalls.length, 0)
  assert.equal(view.phase, 'failed')
  assert.equal(view.title, '下单结果未确认')
  assert.equal(view.showSupport, true)
  assert.doesNotMatch(view.detail ?? '', /已下单|没有扣款/)
})

test('同键重投仍失联时总共只发两次下单，不会递归重试或拉起支付', async () => {
  const h = harness({
    createOrder: { kind: 'network', message: 'offline', queued: false, requestId: 'req-still-offline' },
  })
  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.createOrderCalls.length, 2)
  assert.equal(h.createOrderCalls[1]?.requestId, 'req-still-offline')
  assert.deepEqual(h.delays, [1_000])
  assert.equal(h.paymentCalls.length, 0)
  assert.equal(view.phase, 'failed')
  assert.equal(view.showSupport, true)
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

test('轮询一直 PENDING ⇒ 用完次数后待确认，不宣称款项已收到', async () => {
  const pending = okOutcome<OrderStatusResp>({ status: 'PENDING', rewards: [], retryQueued: null })
  const h = harness({ statuses: [pending], maxPolls: 3 })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(view.phase, 'processing')
  assert.equal(view.title, '支付状态待确认')
  assert.doesNotMatch(view.detail ?? '', /款项已收到|已到账/)
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

test('支付桥失败而服务端已确认到账时，先查本单并显示已发奖励', async () => {
  const h = harness({
    payment: 'failed',
    statuses: [success([{ type: 'RESOURCE', id: 'GOLD', count: 180 }])],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.orderStatusCalls.length, 1)
  assert.equal(view.phase, 'delivered')
  assert.equal(view.rewards[0]?.count, 180)
})

test('支付桥失败且本人订单仍待确认时，不断言没有扣款并给客服入口', async () => {
  const h = harness({
    payment: 'failed',
    statuses: [okOutcome<OrderStatusResp>({ status: 'PENDING', rewards: [], retryQueued: null })],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.orderStatusCalls.length, 1)
  assert.equal(view.phase, 'failed')
  assert.equal(view.title, '支付结果未确认')
  assert.equal(view.showSupport, true, '结果未确认时礼包内应直接给客服入口')
  assert.doesNotMatch(view.detail ?? '', /没有扣款|已到账|款项已收到/,
    '客户端回调不证明订单的最终扣款或发货状态')
  assert.match(view.detail ?? '', /订单|客服/, '要给玩家可执行的查证路径')
})

test('支付桥失败且服务端当前订单失败，也不把可被迟到回调重开的订单说成绝对未扣款', async () => {
  const h = harness({
    payment: 'failed',
    statuses: [okOutcome<OrderStatusResp>({ status: 'FAILED', rewards: [], retryQueued: null })],
  })

  const view = await h.flow.buy('gift_stuck_supply')

  assert.equal(h.orderStatusCalls.length, 1)
  assert.equal(view.phase, 'failed')
  assert.doesNotMatch(view.detail ?? '', /本次没有扣款/)
  assert.equal(view.showSupport, true)
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
    statuses: [okOutcome<OrderStatusResp>({ status: 'PENDING', rewards: [], retryQueued: null })],
  }).flow.buy('gift_stuck_supply')
  const payFailed = await harness({
    createOrder: orderWithNotice(MINOR_NOTICE),
    payment: 'failed',
    statuses: [okOutcome<OrderStatusResp>({ status: 'PENDING', rewards: [], retryQueued: null })],
  }).flow.buy('gift_stuck_supply')

  for (const [phase, view] of [['delivered', delivered], ['processing', processingView],
    ['cancelled', cancelled], ['failed', payFailed]] as const) {
    assert.equal(view.phase, phase, `夹具没造出这一支：${view.phase}`)
    assert.equal(view.minorNotice, MINOR_NOTICE, `${phase} 这一支把提示弄丢了`)
  }
})
