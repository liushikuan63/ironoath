/**
 * 职责：背包与资源产出明细面板的展示数据组装（B04 §2/§3，验收 1、5、6、11）。
 * 依赖：生成的协议类型、core/FixedPoint（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：产量多少由服务端结算，道具能不能用由服务端裁定，
 * 排序由服务端的 sortKey 决定。这里只做「协议 → 展示文本」的搬运。
 *
 * <p><b>产出明细是 B04 §2 点名的「转化关键 UI」</b>，所以有两条硬纪律：
 * <ol>
 *   <li><b>Σ明细必须精确等于 perHour</b>（验收 5：误差 0）。这个不变量由服务端
 *       ResourceOutputCalculator.Breakdown 在构造期强制，但客户端仍然要核一遍并<b>喊出来</b>：
 *       明细加起来和总数对不上，是玩家最容易自己发现的数值造假，
 *       悄悄显示比不显示更糟 —— 所以不吻合时在面板上明写，同时打警告日志</li>
 *   <li><b>满仓必须标红</b>（验收 1/11）。产出停了而玩家不知道，他会以为产量被偷偷改了。
 *       这也是「仓库容量」这个数值唯一的玩家可见出口</li>
 * </ol>
 *
 * <p><b>道具顺序照搬服务端</b>（B04 §3）：sortKey 由服务端按「稀有度 &gt; 类型 &gt; 数量」算好下发，
 * 客户端再排一次就会出现双端顺序不一致 —— 玩家在安卓上看到的背包和 iOS 上不一样，
 * 而这种 bug 没有任何一侧会报错。
 */

import * as FixedPoint from '../../core/FixedPoint'
import type {
  BagItem, BagListResp, OutputBreak, ResourceDetail, ResourceDetailResp,
} from '../../net/generated/BagProtocol'

/** 产出明细的一行。 */
export interface OutputLine {
  /** 来源标签，服务端下发（如「农田 Lv8」「科技加成」），客户端不得自行翻译 */
  readonly source: string
  /** "+600" 或 "+120 (+10%)"。百分比行两个数都要给：只给百分比玩家算不出实际多少 */
  readonly text: string
  readonly isPercent: boolean
}

/** 一种资源的明细。 */
export interface ResourceRow {
  readonly type: string
  readonly currentText: string
  readonly perHourText: string
  /** 受保护不可掠夺量（B04 验收 6）。为 0 时不显示 —— 显示「受保护 0」只是噪音 */
  readonly protectedText: string | null
  readonly full: boolean
  /** 满仓警告文案；未满仓为 null */
  readonly fullText: string | null
  readonly lines: readonly OutputLine[]
  /**
   * Σ明细 是否精确等于 perHour。<b>false 时必须把它显示出来</b>：
   * 这是服务端 ResourceOutputCalculator 的构造期不变量被破坏的信号，
   * 而玩家自己拿计算器一加就能发现 —— 悄悄显示等于默认数值造假
   */
  readonly sumMatches: boolean
  readonly sumText: string
}

/** 背包里的一行道具。 */
export interface BagItemRow {
  readonly itemId: string
  readonly title: string
  readonly rarityText: string
  /** "3/10"：当前堆叠数 / 单堆上限 */
  readonly stackText: string
  /** 来源提示（B04 §3：长按显示「来自：第七章宝箱」）。未配置为 null */
  readonly obtainText: string | null
  readonly sellText: string | null
  /**
   * 使用时是否必须先选目标（B04 §4：加速类道具必须给 targetId）。
   *
   * <p>这一条是显示规则不是判定：面板据此决定「点了直接生效」还是「先弹目标选择」，
   * 服务端仍然会独立校验 targetId 缺失并拒绝。
   */
  readonly needsTarget: boolean
}

/** 按类型分页后的道具。页内顺序照搬服务端的 sortKey 升序。 */
export interface BagPage {
  readonly type: string
  readonly typeText: string
  readonly items: readonly BagItemRow[]
}

/** 整个背包页的数据。 */
export interface BagPanelView {
  readonly capacityText: string
  /** 容量已满时为 true：再获得道具会怎样由服务端裁定，面板只负责提醒 */
  readonly capacityFull: boolean
  readonly pages: readonly BagPage[]
}

/** 整个资源产出明细页的数据。 */
export interface ResourcePanelView {
  readonly rows: readonly ResourceRow[]
  /** 有任意一种资源满仓时的汇总提示 */
  readonly fullWarning: string | null
}

/** 组装资源产出明细页（GET /resource/detail）。行顺序照搬服务端。 */
export function buildResourcePanel(resp: ResourceDetailResp): ResourcePanelView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const rows = resp.resources.map((detail: ResourceDetail): ResourceRow => buildResourceRow(detail))
  const fullTypes: string[] = []
  for (const row of rows) {
    if (row.full) {
      fullTypes.push(row.type)
    }
  }
  return {
    rows,
    fullWarning: fullTypes.length === 0
      ? null
      : `${fullTypes.join('、')} 已满仓，产出已停止。扩建仓库或消耗掉一部分后才会恢复`,
  }
}

/** 组装一种资源的明细行。 */
export function buildResourceRow(detail: ResourceDetail): ResourceRow {
  if (detail === undefined || detail === null) {
    throw new Error('detail 不得为空')
  }
  const lines = detail.breakdown.map((item: OutputBreak): OutputLine => buildOutputLine(item))
  let sum = 0
  for (const item of detail.breakdown) {
    sum += item.amount
  }
  const matches = sum === detail.perHour
  if (!matches) {
    // 明细之和与实际产量对不上是服务端的构造期不变量被破坏了。
    // 打日志是为了让排查有线索，但面板上也必须写出来 —— 玩家自己加一遍就能发现
    console.warn(`[BagPanel] ${detail.type} 产出明细之和 ${sum} ≠ 实际每小时产量 ${detail.perHour}`
      + '（B04 验收 5 要求误差为 0，这是服务端 ResourceOutputCalculator 的不变量被破坏）')
  }
  return {
    type: detail.type,
    currentText: `${detail.current}/${detail.cap}`,
    perHourText: `每小时 +${detail.perHour}`,
    protectedText: detail.protectedAmount > 0 ? `受保护 ${detail.protectedAmount}` : null,
    full: detail.full,
    fullText: detail.full ? '已满仓，停产' : null,
    lines,
    sumMatches: matches,
    sumText: matches
      ? `合计 每小时 +${sum}`
      : `合计 每小时 +${sum}（与实际产量 ${detail.perHour} 不符，已上报）`,
  }
}

/**
 * 组装一行产出明细。
 *
 * <p>百分比行显示成「+120 (+10%)」而不是只显示其中一个：
 * 只显示百分比，玩家算不出这一条到底给了多少；只显示绝对值，
 * 他又看不出这是加成还是基础产量 —— 而这两者在「该升科技还是该升建筑」上是完全不同的决策。
 */
export function buildOutputLine(item: OutputBreak): OutputLine {
  if (item.isPercent) {
    if (item.percentFixed === null) {
      // isPercent=true 却没有 percentFixed，这条明细没法解释。照实说出来而不是编一个数
      return { source: item.source, text: `+${item.amount}（百分比缺失）`, isPercent: true }
    }
    return {
      source: item.source,
      text: `+${item.amount} (+${FixedPoint.percentText(item.percentFixed)})`,
      isPercent: true,
    }
  }
  return { source: item.source, text: `+${item.amount}`, isPercent: false }
}

/** 组装背包页（GET /bag/list）。分页不改顺序：页内严格保持服务端给的 sortKey 升序。 */
export function buildBagPanel(resp: BagListResp): BagPanelView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  // 先按类型累加到可变数组，最后一次性冻结成视图：
  // 直接往 readonly 数组上 push 需要强转，那等于把类型检查关掉，不如老实分两步
  const order: string[] = []
  const grouped = new Map<string, BagItemRow[]>()
  for (const item of resp.items) {
    let bucket = grouped.get(item.type)
    if (bucket === undefined) {
      bucket = []
      grouped.set(item.type, bucket)
      order.push(item.type)
    }
    bucket.push(buildItemRow(item))
  }
  // 页签顺序 = 各类型在响应里首次出现的顺序，也就是服务端 sortKey 排序后的自然顺序。
  // 客户端不自己定页签顺序，否则双端的分页会不一样
  const pages: BagPage[] = order.map((type) => ({
    type,
    typeText: itemTypeText(type),
    items: grouped.get(type) ?? [],
  }))
  return {
    capacityText: `背包 ${resp.capacityUsed}/${resp.capacityMax}`,
    capacityFull: resp.capacityUsed >= resp.capacityMax,
    pages,
  }
}

/**
 * 出售能力整条撤下（2026-09-13 裁决）：**服务端不再下发 `sellable` / `sellPriceGold`**
 * （协议里已删，见 bag.schema.json 的说明），因为服务端没有 `/bag/sell` 端点、
 * B04 整篇没有「出售」这条规则（什么档能卖、按表价还是折扣、金币走不走每日保护额度、
 * 能不能买低卖高刷金，全是空的 —— 收口清单 #47 ③）。
 *
 * <p>原先的形态是「表里有字段 + 协议里有字段 + UI 有个开关关着的按钮」，
 * 那是最容易被人顺手实现成刷金入口的形状；现在只剩「表里有数据 + 文档里无规则」，
 * 定完规则再让字段随协议回来。`sellText` 这个行字段保留（视图据此决定按钮亮不亮），
 * 但在这里就是恒 null —— 一份不存在的能力不该有第二条通路。
 */
export function buildItemRow(item: BagItem): BagItemRow {
  if (item === undefined || item === null) {
    throw new Error('item 不得为空')
  }
  return {
    itemId: item.itemId,
    // 名字来自配置表，客户端不得自行翻译（协议明写）
    title: `${item.name} ×${item.count}`,
    rarityText: item.rarity,
    stackText: `${item.count}/${item.stackMax}`,
    obtainText: item.obtainFrom === null || item.obtainFrom.length === 0
      ? null
      : `来自：${item.obtainFrom}`,
    sellText: null,
    needsTarget: item.type === 'SPEEDUP',
  }
}

/**
 * 道具类型的中文页签名。
 *
 * <p>协议只给了英文枚举值（SPEEDUP / RESOURCE / CHEST / MATERIAL / BUFF），
 * 而道具<b>名字</b>是服务端下发的中文。页签名属于界面外壳文案而不是配置数据，
 * 所以在这里翻译；不认识的类型原样显示 —— 静默变成空白页会让玩家以为道具丢了。
 */
export function itemTypeText(type: string): string {
  switch (type) {
    case 'SPEEDUP': return '加速'
    case 'RESOURCE': return '资源'
    case 'CHEST': return '宝箱'
    case 'MATERIAL': return '材料'
    case 'BUFF': return '增益'
    default: return type
  }
}
