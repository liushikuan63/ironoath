/**
 * 职责：抽卡概率公示面板的数据组装（B06 §6 合规、验收 3）。
 * 依赖：生成的配置类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>这个模块存在的唯一理由是合规</b>。B06 §6 写的是「不做完不许上线付费」，
 * 禁止项里又单独写了一条「不要将抽卡概率写死在代码或客户端」。
 * 所以面板上的每一个数字都必须能追溯到 gacha 表的某一行某一列 ——
 * 本模块不做任何计算，只做「配置 → 展示文本」的搬运。
 *
 * <p><b>展示的是公示概率（综合概率，含保底），不是每抽基础概率。</b>
 * 两组数都在 gacha 表里：`ssrChance` 是公示值，`ssrBaseChance` 是引擎实际掷的基础值，
 * 后者由 tools/gacha-calibrate 反解校准，使「基础概率 + 保底」跑出来的实际频率等于公示值。
 * 面板若错拿基础概率去展示，玩家实测到的出率会高于面板数字 ——
 * 那对玩家是好事，但仍然是公示不实，监管口径上一样不合规。
 *
 * <p><b>定点数不要在客户端还原成 double</b>：概率以 ×10000 的整数存着，
 * 格式化成百分比时用整数除法与取余，全程不出现浮点。
 * 用 double 会在 0.02 这种值上得到 1.9999999%，而公示文案里写的是 2%（B06 禁止项：不要用 double 表示概率）。
 */

import type { GachaCfg } from '../../config/generated/ConfigTypes'

/** 稀有度档位。顺序即公示顺序（从高到低），与 Tier / HeroRarity 一致。 */
export type Rarity = 'SSR' | 'SR' | 'R' | 'N'

/** 面板上的一档概率。 */
export interface TierDisclosure {
  readonly rarity: Rarity
  /** 定点概率（×10000），原样透传配置值，不做任何换算 */
  readonly rateFixed: number
  /** 展示用文本，如 "2%" 或 "1.5%"。由整数运算得出，不含浮点 */
  readonly text: string
}

/** 一份完整的公示内容。 */
export interface GachaDisclosure {
  readonly poolId: string
  readonly poolName: string
  readonly tiers: readonly TierDisclosure[]
  readonly ssrPity: number
  readonly srPity: number
  /** 单抽消耗。costResource 与 costItemId 恰好一个非空（由服务端一致性测试断言） */
  readonly costText: string
  readonly lifetimeLimitText: string
  /**
   * 合规公示原文，必须<b>原样</b>展示。
   *
   * gacha 表里写明「客户端抽卡界面必须原文呈现，不得删减、折叠或以图标替代」，
   * 所以这里不做任何加工，也不提供「精简版」—— 提供就一定会有人用。
   */
  readonly disclosureText: string
}

/** 定点概率（×10000）→ 百分比文本。全程整数运算。 */
export function formatRate(rateFixed: number): string {
  if (!Number.isInteger(rateFixed)) {
    throw new Error(`概率必须是定点整数（×10000），实际=${rateFixed}。`
      + '用小数传入说明上游已经把定点数还原成了 double，那会丢掉精度')
  }
  if (rateFixed < 0 || rateFixed > 10000) {
    throw new Error(`概率越界：${rateFixed}（定点区间应为 [0, 10000]）`)
  }
  const whole = Math.floor(rateFixed / 100)
  const frac = rateFixed % 100
  if (frac === 0) {
    return `${whole}%`
  }
  // 最多两位小数，去掉末尾的 0：600 → "6%"，150 → "1.5%"，25 → "0.25%"
  const fracText = frac < 10 ? `0${frac}` : `${frac}`
  const trimmed = fracText.replace(/0+$/, '')
  return trimmed.length === 0 ? `${whole}%` : `${whole}.${trimmed}%`
}

/** 从 gacha 表的一行组装公示面板。 */
export function buildDisclosure(pool: GachaCfg): GachaDisclosure {
  if (pool === undefined || pool === null) {
    throw new Error('pool 不得为空')
  }
  const tiers: TierDisclosure[] = [
    { rarity: 'SSR', rateFixed: pool.ssrChance, text: formatRate(pool.ssrChance) },
    { rarity: 'SR', rateFixed: pool.srChance, text: formatRate(pool.srChance) },
    { rarity: 'R', rateFixed: pool.rChance, text: formatRate(pool.rChance) },
    { rarity: 'N', rateFixed: pool.nChance, text: formatRate(pool.nChance) },
  ]
  const sum = tiers.reduce((acc, t) => acc + t.rateFixed, 0)
  if (sum !== 10000) {
    // 四档之和必须恰为 100%。这条在服务端也有断言，客户端再查一次是刻意的：
    // 面板是玩家唯一能看到的数字，配置被改坏时这里应当立刻炸，
    // 而不是把一个加起来 99% 的面板摆到玩家面前
    throw new Error(`卡池 ${pool.id} 四档公示概率之和必须恰为 10000（100%），实际=${sum}`)
  }
  if (pool.disclosureText === undefined || pool.disclosureText.trim().length === 0) {
    throw new Error(`卡池 ${pool.id} 缺少公示文案，合规要求必须原文展示`)
  }

  const hasItem = pool.costItemId !== undefined && pool.costItemId !== null
  const hasResource = pool.costResource !== undefined && pool.costResource !== null
  if (hasItem === hasResource) {
    throw new Error(`卡池 ${pool.id} 必须恰好指定一种计价方式：`
      + `costItemId=${pool.costItemId}, costResource=${pool.costResource}`)
  }
  const costText = `${pool.costCount} ${hasResource ? pool.costResource : pool.costItemId}`

  return {
    poolId: pool.id,
    poolName: pool.name,
    tiers,
    ssrPity: pool.ssrPity,
    srPity: pool.srPity,
    costText,
    lifetimeLimitText: pool.lifetimeLimit > 0
      ? `每个账号限抽 ${pool.lifetimeLimit} 次`
      : '不限次数',
    disclosureText: pool.disclosureText,
  }
}
