/**
 * 职责：定点数工具的客户端镜像 —— 与服务端 game-common 的 FixedPoint 同一套语义。
 * 依赖：无（引擎无关）。
 *
 * ⚠️ 用途边界（B00 铁律 2、3）：客户端的定点运算<b>只用于表现层预测</b>
 *   （产出倒计时、预计收益提示）。任何进入数值判定或结算的结果一律以服务端下发为准，
 *   收到后强制纠偏。删掉本文件的全部调用，游戏行为不应发生任何变化。
 *
 * 精度说明：JS number 是 IEEE-754 double，整数在 |x| < 2^53 范围内精确。
 * 定点数放大 10000 倍后，可精确表示的真实值上限约 9.0e14 —— 对资源量与产量完全够用。
 * 超出该范围会抛错而不是静默失真，因为静默失真会让「客户端显示 100 万、服务端实际 99 万」
 * 这类问题变成玩家投诉而不是代码报错。
 *
 * 舍入：与服务端一致使用 HALF_UP（四舍五入，负数向远离零方向进位）。
 * 不用 Math.round —— 它对负数是向 +∞ 取整（Math.round(-1.5) === -1），与服务端差 1。
 */

/** 定点放大倍数，来源：contract/config/global.json 的 FIXED_POINT_SCALE。 */
export const SCALE = 10000

/** 定点 0。 */
export const ZERO = 0

/** 定点 1.0。 */
export const ONE = SCALE

function assertSafeInteger(value: number, context: string): void {
  if (!Number.isSafeInteger(value)) {
    throw new RangeError(`定点数运算超出 JS 安全整数范围（${context}）：${value}`)
  }
}

/** 整数转定点：3 ⇒ 30000。 */
export function of(whole: number): number {
  assertSafeInteger(whole, 'of')
  const result = whole * SCALE
  // 入参安全不代表乘积安全：MAX_SAFE_INTEGER × 10000 早已丢精度，必须一起拦
  assertSafeInteger(result, 'of')
  return result
}

/**
 * 十进制字符串转定点："1.18" ⇒ 11800。
 *
 * 与服务端同样严格：小数位数超过 4 位直接抛错，不静默截断。
 */
export function parse(decimal: string): number {
  const text = decimal.trim()
  if (text.length === 0) {
    throw new Error('定点数解析入参不得为空')
  }
  if (!/^[+-]?(\d+)(\.\d+)?$/.test(text)) {
    throw new Error(`不是合法的十进制数：${decimal}`)
  }
  const dot = text.indexOf('.')
  const fractionDigits = dot < 0 ? 0 : text.length - dot - 1
  if (fractionDigits > 4) {
    throw new RangeError(`小数位数超过定点精度（4 位），无法精确表示：${decimal}`)
  }
  const value = Number(text) * SCALE
  // Number(text) * SCALE 可能引入 1e-12 级浮点尾巴（如 0.29 * 10000 = 2899.9999999999995）
  const rounded = Math.round(value)
  if (Math.abs(value - rounded) > 1e-6) {
    throw new RangeError(`定点转换出现异常误差：${decimal} ⇒ ${value}`)
  }
  return rounded
}

/** 定点转真实值：11800 ⇒ 1.18。仅用于展示，不得再参与结算。 */
export function toNumber(fixed: number): number {
  return fixed / SCALE
}

/** 定点转可读字符串，去掉尾随零：11800 ⇒ "1.18"。与服务端 FixedPoint.format 一致。 */
export function format(fixed: number): string {
  const text = (fixed / SCALE).toFixed(4)
  return text.replace(/\.?0+$/, '')
}

/**
 * 定点比率（×10000）转百分比文本：1000 ⇒ "10%"，150 ⇒ "1.5%"，25 ⇒ "0.25%"。
 *
 * <p>先乘 100 再交给 {@link format}，尾零裁剪与小数位数因此和双端其它定点展示完全一致。
 *
 * <p><b>与 game/gacha/GachaDisclosure.formatRate 的分工</b>：那个是抽卡合规公示专用实现，
 * 全程整数运算且带 [0, 10000] 的越界校验（公示数字要被监管核对，多一道防线是值得的）；
 * 本函数是通用展示用，不做范围校验 —— 产出加成、科技加成之类的比率本来就可以超过 100%。
 * 两者对同一输入给出的字符串相同，这一点由各自的单测分别钉住。
 */
export function percentText(fixed: number): string {
  return `${format(fixed * 100)}%`
}

/**
 * HALF_UP 整数除法。与服务端 FixedPoint.divideHalfUp 同语义。
 *
 * 用「商 + 余数比较」而不是 Math.round(num/den)：后者在负数上向 +∞ 取整，会与服务端差 1。
 */
function divideHalfUp(numerator: number, denominator: number): number {
  if (denominator === 0) {
    throw new RangeError('定点数除零')
  }
  const quotient = Math.trunc(numerator / denominator)
  const remainder = Math.abs(numerator % denominator)
  const absDenominator = Math.abs(denominator)
  if (remainder === 0) {
    return quotient
  }
  // remainder * 2 >= absDenominator 等价于 |余数/除数| >= 0.5
  if (remainder * 2 >= absDenominator) {
    return quotient + (numerator > 0 === denominator > 0 ? 1 : -1)
  }
  return quotient
}

export function add(a: number, b: number): number {
  const result = a + b
  assertSafeInteger(result, 'add')
  return result
}

export function sub(a: number, b: number): number {
  const result = a - b
  assertSafeInteger(result, 'sub')
  return result
}

/** 定点乘法：mul(11800, 20000) ⇒ 23600（1.18 × 2.0 = 2.36）。 */
export function mul(a: number, b: number): number {
  const product = a * b
  if (!Number.isSafeInteger(product)) {
    throw new RangeError(`定点乘法中间积超出安全整数范围：${a} × ${b} = ${product}`)
  }
  return divideHalfUp(product, SCALE)
}

/** 定点除法：div(23600, 11800) ⇒ 20000（2.36 ÷ 1.18 = 2.0）。 */
export function div(a: number, b: number): number {
  if (b === 0) {
    throw new RangeError(`定点数除零：被除数=${format(a)}`)
  }
  const scaled = a * SCALE
  if (!Number.isSafeInteger(scaled)) {
    throw new RangeError(`定点除法中间积超出安全整数范围：${a} × ${SCALE} = ${scaled}`)
  }
  return divideHalfUp(scaled, b)
}

/** 按百分比取值：percent(2000, parse("0.15")) ⇒ 300。 */
export function percent(value: number, percentFixed: number): number {
  return mul(value, percentFixed)
}

/** 定点转整数，HALF_UP：15000 ⇒ 2，-15000 ⇒ -2。 */
export function round(fixed: number): number {
  return divideHalfUp(fixed, SCALE)
}

/** 定点转整数，向零截断：19999 ⇒ 1，-19999 ⇒ -1。 */
export function truncate(fixed: number): number {
  return Math.trunc(fixed / SCALE)
}

/** 夹在 [lo, hi] 闭区间内。区间非法时抛错，不静默交换。 */
export function clamp(value: number, lo: number, hi: number): number {
  if (lo > hi) {
    throw new RangeError(`clamp 区间非法：lo=${format(lo)} > hi=${format(hi)}`)
  }
  return value < lo ? lo : value > hi ? hi : value
}

/**
 * 几何型成长曲线：base × ratio^exponent，用于建筑时间/消耗的倒计时显示。
 *
 * ⚠️ 与服务端 FixedPoint.geometric 的差别：服务端在 BigDecimal 上精确算完再舍入一次，
 * 这里只能逐步定点相乘、每步舍入一次，因此高等级下可能与服务端相差数个定点单位。
 * 这是可接受的 —— 客户端用它只为了画倒计时，真实数值一律以服务端下发为准（铁律 3）。
 * 需要与服务端逐位一致的场合，必须由服务端算好下发，不要在客户端复算。
 */
export function geometric(baseFixed: number, ratioFixed: number, exponent: number): number {
  if (exponent < 0) {
    throw new RangeError(`geometric 的指数不得为负：${exponent}`)
  }
  let result = baseFixed
  for (let i = 0; i < exponent; i++) {
    result = mul(result, ratioFixed)
  }
  return result
}
