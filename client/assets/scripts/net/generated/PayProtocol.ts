/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 订单状态。取值必须与 game-core 的 PayOrder.Status 一致（由 PayContractParityTest 断言）。
 *
 * FAILED 与「发货失败」不是一件事：FAILED 是支付本身没成功（取消、超时、验签不过），发货失败时订单仍然是 SUCCESS 并进补单队列 —— 玩家已经付了钱，把订单标成 FAILED 等于账面上否认收到过这笔钱。
 */
export type OrderStatus =
  | 'PENDING'
  | 'SUCCESS'
  | 'FAILED'

/**
 * 一个商品的价格。**由服务端下发，客户端不得内置任何价格**：不同渠道/地区的定价可以不同，而内置价格的客户端在调价时必须发版 —— 更糟的是，发版前的旧客户端会显示旧价格却按新价格扣款。
 */
export interface ProductPrice {
  /** 商品 id，形如 monthly_card / growth_fund / first_charge。 */
  productId: string
  /** 价格，单位是分。来源 global.PRODUCT_*_CENTS。 */
  cents: number
  /** 币种（ISO 4217，如 CNY）。客户端据此决定货币符号的位置与小数位数 —— 写死「¥」的话，出海时每一个界面都要改。 */
  currency: string
  /** 划线价（分），无折扣时为 null。**为 null 时客户端不得显示划线** —— 显示一个等于现价的划线价是价格欺诈的常见形态，而监管对这一条查得很细。 */
  originalCents: number | null
}

/**
 * GET /pay/prices 响应：价格表。
 */
export interface PricesResp {
  /** 全部在售商品。下架的商品不出现在这里，而不是标一个「已下架」—— 客户端拿到一个买不了的商品只会做出一个灰掉的按钮，而玩家会以为是 bug。 */
  products: ProductPrice[]
  /** 本次下发的地区口径。客户端要把它记在缓存里：换区之后价格表必须重新拉，否则会用旧区的价格下单，而服务端按新区扣款。 */
  region: string
}

/**
 * 调起微信虚拟支付（米大师）所需的参数。**字段全部是字符串**：这些值由渠道生成，服务端只做透传，任何一侧试图解析或重算它们都会在渠道改格式时静默出错。
 */
export interface PayParams {
  /** 米大师支付模式（game / short_series_goods 等），由服务端按渠道配置下发。 */
  mode: string
  /** 米大师应用 id。**这是部署参数不是游戏数值**，来自环境变量而不是配置表 —— 配置表会进版本库，而 offerId 与密钥同属一类凭据。 */
  offerId: string
  /** 下单金额（**分**），字符串形式透传 —— 服务端发的是 line.totalCents()，也就是这一单的应收金额。 2026-09-13 裁决：语义以代码现状（金额）为准，并改掉本字段此前那句「购买数量（游戏币个数）」。理由是全条支付链只有 totalCents 一个真实生产者，没有任何一处产出过「个数」这种语义，而 global 里的 PRODUCT_*_CENTS 本身就是分。 **接入真实渠道之前仍须拿米大师正式文档复核**：两种读法的实收金额可以差 100 倍，而唯一能定它的是官方文档（本地没有凭据，check-contract-sync 也查不出「描述与代码意图不一致」）。见收口清单 #49 与 §三·补 A2。 */
  buyQuantity: string
  /** 环境（0 正式 / 1 沙箱）。**沙箱参数绝不能出现在正式包里**，所以它由服务端下发而不是客户端写死 —— 客户端写死的话，一次忘了改的提交就会让正式包指向沙箱。 */
  env: string
  /** 币种，与 ProductPrice.currency 同源。 */
  currencyType: string
  /** 渠道要求的签名。为 null 表示当前环境不需要签名（本地开发）—— 而**正式环境必须有**，所以服务端在正式环境下不下发 null，客户端也不该把 null 当成正常情况静默放过。 */
  signature: string | null
}

/**
 * POST /pay/order 请求体：下单。
 */
export interface CreateOrderReq {
  /** 幂等键。下单必须幂等：弱网下客户端重发一次下单请求，若不幂等就会造出两笔订单，而玩家只会付其中一笔的钱 —— 另一笔会永久停在 PENDING，最后在补单队列里变成一条谁也说不清的记录。 */
  requestId: string
  /** 商品 id。**价格不由客户端传**：客户端传价格等于把定价权交出去，改一下请求体就能一分钱买月卡。价格永远由服务端按 productId 查表。 */
  productId: string
  /** 购买份数。有单次限购的商品由服务端按限购规则裁剪或拒绝，客户端传的只是意愿。 */
  count: number
}

/**
 * 下单结果：订单号 + 调起支付所需的参数。
 */
export interface CreateOrderResp {
  /** 服务端订单号。**回调验签与补单都以它为唯一键** —— 用渠道的 transactionId 做键是不行的，因为取消后重新支付会产生新的 transactionId，而那是同一笔订单。 */
  orderId: string
  /** 调起支付所需参数。 */
  payParams: PayParams
}

/**
 * POST /pay/callback 请求体：渠道支付结果回调。
 *
 * **这个端点不校验玩家身份**：调用方是渠道服务器而不是客户端，所以它的可信性完全靠 sign 验签，而不是靠 X-Player-Id 头。
 */
export interface PayCallbackReq {
  /** 服务端订单号（下单时下发的那个）。 */
  orderId: string
  /** 渠道流水号。同一个 orderId 可能对应多个 transactionId（取消后重付），所以它不能当幂等键，只作为凭据留存。 */
  transactionId: string
  /** 渠道签名。验签失败必须拒绝（PAY_SIGN_INVALID）—— 不验签的回调端点等于任何人 POST 一下就能给自己发货。 */
  sign: string
  /** 渠道告知的支付结果。false 时订单转为 FAILED，不发货。 */
  success: boolean | null
}

/**
 * 一项已发放的奖励。type 是裸字符串（取值同 bag 协议的 RewardType），理由见本文件 description 末尾的取舍说明。
 */
export interface PayRewardItem {
  /** 奖励类别：RESOURCE / ITEM / HERO / HERO_FRAGMENT / STAMINA 等，取值与 bag 协议的 RewardType 一致（由 parity 测试钉住）。 */
  type: string
  /** 类别内的具体 id（资源 id / item 表行 id / 武将 id）。 */
  id: string
  /** 数量。 */
  count: number
}

/**
 * GET /pay/order 响应：订单当前状态与已发放的奖励。
 *
 * **客户端在支付返回后必须轮询这个端点**，不能凭 `wx.requestMidasPayment` 的成功回调直接发货显示 —— 那个回调只表示「渠道侧完成了」，而发货是服务端在收到渠道服务器回调之后才做的。凭客户端回调直接显示「已到账」，会在补单场景下变成一句谎话。
 */
export interface OrderStatusResp {
  /** 订单状态。 */
  status: OrderStatus
  /** 已发放的奖励。SUCCESS 但 rewards 为空表示「钱收到了、货还在补单队列里」—— 这是一种必须能表达的状态，否则客户端只能显示「已购买」而玩家什么都还没拿到。 */
  rewards: PayRewardItem[]
  /** 是否已进补单队列。为 true 时客户端应当显示「发货处理中」并给出客服入口，而不是显示失败 —— 钱已经收了，说失败会引发退款投诉。 */
  retryQueued: boolean | null
}

/**
 * POST /pay/retry 请求体：手工触发一次补单（客服入口用）。
 */
export interface PayRetryReq {
  /** 幂等键。 */
  requestId: string
  /** 要补发的订单号。 */
  orderId: string
}

/**
 * 一条抽取记录（合规要求可查）。
 */
export interface GachaRecord {
  /** 抽取时刻（服务端时间戳，铁律 5）。 */
  time: number
  /** 卡池 id。记录必须带池子：不同池子的概率不同，不带池子的记录无法用来核对公示概率。 */
  poolId: string
  /** 抽到的武将 id。 */
  heroId: string
  /** 这一抽是否由保底触发。**必须下发**：公示里写了保底，玩家就要能在自己的记录里看到保底确实生效过 —— 一个从未标记过保底的记录列表，等于让玩家只能相信而无法验证。 */
  isPity: boolean
}

/**
 * GET /gacha/history 响应：最近 50 次抽取记录（B15 §三 合规要求）。
 *
 * 上限 50 来自 B15 文档；服务端日志保留 90 天（global.GACHA_LOG_RETENTION_DAYS），两者是不同的口径 —— 50 条是给玩家看的窗口，90 天是给监管与客服取证的窗口。
 */
export interface GachaHistoryResp {
  /** 最近 50 条，按时间倒序（最新的在前）。 */
  records: GachaRecord[]
  /** 日志保留天数，来自 global.GACHA_LOG_RETENTION_DAYS。**下发它是为了合规可核**：监管问「你们保留多久」时，答案应当能在产品里被玩家和检查者同时看到，而不是只在某个文档里。 */
  retentionDays: number
  /** 服务端时间戳。 */
  serverNow: number
}
