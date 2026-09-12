/**
 * 职责：关卡面板的展示数据组装（B09 §三/§6，验收 1、9）—— 把服务端下发的关卡与结算结果
 *       翻译成面板要显示的文本。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：星级用服务端下发的 {@code stars.total}，
 * 绝不自己数那三个布尔值（数一遍就等于把口径搬到客户端）；能不能打由 {@code unlocked} 决定，
 * 体力够不够由服务端在结算时裁定，这里只把结果说出来。
 *
 * <p><b>三条纪律</b>，都来自协议注释里写明的要求：
 * <ol>
 *   <li>「没打过」与「打过但 0 星」是两种状态，必须能区分。协议为此专门拆了
 *       {@code attempted} 与 {@code progress} 两个字段 —— 用一个全 0 的对象表达不了这个区别</li>
 *   <li>未解锁必须给原因（{@code lockedReason}），原样展示。一个灰掉的关卡不说明为什么，
 *       玩家会以为是 bug（B08 的同一条纪律：绝不静默失败）</li>
 *   <li>失败不扣体力必须可见。{@code staminaCost} 与 {@code staminaCharged} 两个字段都下发，
 *       就是为了让你能把「应扣 6 / 实扣 0」摆出来，否则玩家会以为体力被偷扣了</li>
 * </ol>
 */

import type {
  BossMechanic,
  ChallengeStageResp,
  StageEntry,
  StageListResp,
  SweepResp,
  UnitRestriction,
} from '../../net/generated/StageProtocol'

/** 关卡列表的一行。 */
export interface StageRow {
  readonly stageId: string
  /** 「第 1 章 · 3/10 关卡名」这样的标题。章节与序号都由服务端给，客户端不排序 */
  readonly title: string
  /** 星级文本：未挑战 / 0 星 / N 星。三态必须可区分 */
  readonly starText: string
  /** 星数与回合记录的补充说明。逐条达成情况只在挑战结算里有，理由见 conditionText */
  readonly conditionText: string
  /** 入场门槛（兵种限制）文本；无限制时为 null */
  readonly restrictionText: string | null
  /** BOSS 机制文本；无机制时为 null */
  readonly mechanicText: string | null
  readonly costText: string
  /** 是否可以点。等于服务端的 unlocked —— 本模块不自己判断体力够不够 */
  readonly tappable: boolean
  /** 未解锁原因，原样透传；已解锁为 null */
  readonly lockedReason: string | null
}

/** 整个关卡列表页的数据。 */
export interface StageListView {
  readonly rows: readonly StageRow[]
  readonly staminaText: string
  /** 有未解锁关卡时提示「差什么」，全部解锁则为 null */
  readonly lockedCountText: string | null
}

/** 一次挑战的结算摘要。 */
export interface ChallengeSummary {
  readonly starsText: string
  /** 本次新得的星数（不是历史最好）。为 0 时表示这次没打出新星 */
  readonly earnedText: string
  /** 是否刷新历史最好成绩 —— 客户端据此播「新纪录」动效 */
  readonly newBest: boolean
  readonly conditionText: string
  readonly rewardLines: readonly string[]
  /** 逐阶级的损失。只给总数的话玩家看不出掉的是 T1 还是 T5，而两者代价差一个数量级 */
  readonly lossLines: readonly string[]
  readonly staminaText: string
  /** 失败不扣体力时为 true，面板必须把这件事说出来 */
  readonly refunded: boolean
  readonly reportId: string
}

/** 一次批量扫荡的结算摘要。 */
export interface SweepSummary {
  /** 「实际扫荡 7 / 10 次」。少于请求次数时必须说明，否则玩家无法解释少了的奖励 */
  readonly executedText: string
  readonly shortfallText: string | null
  readonly rewardLines: readonly string[]
  readonly staminaText: string
  /** 每次扫荡都是一场独立战斗，各自的战报都要能点开 */
  readonly reportIds: readonly string[]
}

/** 组装关卡列表页。行顺序照搬服务端，本模块不排序、不过滤。 */
export function buildStageList(resp: StageListResp): StageListView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const rows = resp.stages.map((entry: StageEntry): StageRow => buildStageRow(entry))
  let locked = 0
  for (const row of rows) {
    if (!row.tappable) {
      locked++
    }
  }
  return {
    rows,
    staminaText: `体力 ${resp.stamina}`,
    lockedCountText: locked === 0 ? null : `${locked} 个关卡尚未解锁`,
  }
}

/** 组装一行关卡。 */
export function buildStageRow(entry: StageEntry): StageRow {
  if (entry === undefined || entry === null) {
    throw new Error('entry 不得为空')
  }
  return {
    stageId: entry.stageId,
    title: `${entry.chapterId} · 第 ${entry.stageNo} 关 ${entry.name}`,
    starText: starText(entry),
    conditionText: conditionText(entry),
    restrictionText: restrictionText(entry.unitRestriction),
    mechanicText: mechanicText(entry.bossMechanic),
    costText: `体力 ${entry.staminaCost} · 限 ${entry.roundLimit} 回合`,
    tappable: entry.unlocked,
    lockedReason: entry.lockedReason,
  }
}

/**
 * 星级文本。
 *
 * <p><b>三态必须可区分</b>：未挑战 / 0 星 / N 星。协议为此专门下发了 {@code attempted} ——
 * 「没打过」显示 0 星会让玩家以为自己打过并且打砸了，从而不敢再点。
 *
 * <p>星数一律取 {@code progress.stars}（服务端算好的总数），
 * <b>绝不数那三个布尔值</b>：数一遍就等于把星级口径搬到客户端，
 * 而服务端已经明确「冗余下发 total 就是因为不想让客户端自己数」。
 */
export function starText(entry: StageEntry): string {
  if (!entry.attempted) {
    return '未挑战'
  }
  return `${entry.progress.stars} 星`
}

/**
 * 关卡行上的补充说明。
 *
 * <p><b>这里刻意不列出「三条里达成了哪几条」</b>：三星是三个<b>互相独立</b>的条件之和，
 * 从总数反推是会错的 —— 2 星完全可能是「通关 + 限时」而没有无损，
 * 若按 stars>=2 就断言「无损达成」，面板会告诉玩家一个假消息，比不显示更糟。
 * 而 {@code StageProgressView} 只带 stars 总数与 bestRounds，
 * 三个布尔值（StageStars）只出现在单次战斗的响应里，历史最好成绩没带。
 *
 * <p>所以列表页只显示能确证的事实：已得星数、历史最少回合、回合上限。
 * 「差的是哪一条」在挑战结算页显示（那里有本次的三个布尔值）。
 *
 * <p>TODO(B09 缺口): 协议应在 StageProgressView 里补上历史最好成绩的三个布尔值。
 * 协议注释自己写了「玩家看到 2/3 时需要知道差的是哪一条，否则他只能反复试」——
 * 而列表页恰恰是最需要这条信息的地方（玩家是在列表页决定要不要重试的）。
 */
export function conditionText(entry: StageEntry): string {
  const progress = entry.progress
  if (!entry.attempted) {
    return '三星条件：通关 / 无损 / 限时'
  }
  const rounds = progress.bestRounds > 0
    ? `历史最少 ${progress.bestRounds} 回合`
    : '尚无回合记录'
  return `已得 ${progress.stars}/3 星 · ${rounds} · 上限 ${entry.roundLimit} 回合`
}

/**
 * 兵种限制的文案。
 *
 * <p><b>这是入场门槛，不是三星条件之一</b>（协议注释里明写这是对 B09 §4 字面表述的有意偏离）：
 * 若做成「不满足就少一颗星」，玩家仍然可以带违规阵容进场，限制就成了装饰。
 * 所以文案要说成「限制」而不是「目标」。<b>本模块不判断玩家的阵容是否满足它</b> ——
 * 那需要读军队数据并做匹配，属于服务端入场校验（ErrorCode 9002）。
 */
export function restrictionText(restriction: UnitRestriction): string | null {
  switch (restriction) {
    case 'NONE': return null
    case 'NO_SIEGE': return '禁带攻城器'
    case 'CAVALRY_ONLY': return '仅限骑兵'
    case 'RANGED_ONLY': return '仅限远程'
    default: return restriction
  }
}

/**
 * BOSS 机制的文案。
 *
 * <p>内核尚未实现的机制，服务端会明确拒绝（NOT_IMPLEMENTED）而不是当普通关打。
 * 面板仍然要把机制名显示出来：否则玩家点进去只看到一个报错，
 * 而机制补上之后同一关突然变难，会被理解成偷偷加强。
 */
export function mechanicText(mechanic: BossMechanic): string | null {
  switch (mechanic) {
    case 'NONE': return null
    case 'REINFORCEMENT': return 'BOSS 机制：增援'
    case 'SHIELD_PHASE': return 'BOSS 机制：护盾阶段'
    case 'COUNTER_STRIKE': return 'BOSS 机制：反击'
    default: return mechanic
  }
}

/** 组装一次挑战的结算摘要。 */
export function buildChallengeSummary(resp: ChallengeStageResp): ChallengeSummary {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const refunded = resp.staminaCharged === 0 && resp.staminaCost > 0
  return {
    starsText: `${resp.progress.stars} 星（历史最好）`,
    earnedText: resp.starsEarned > 0 ? `本次新得 ${resp.starsEarned} 星` : '本次未得新星',
    newBest: resp.newBest,
    conditionText: starConditionText(resp.stars.cleared, resp.stars.noLoss, resp.stars.withinRounds),
    rewardLines: resp.rewards.map((reward) => `${reward.name} ×${reward.count}`),
    lossLines: resp.losses.map((loss) => `${loss.unitId} −${loss.count}`),
    // 两个字段都摆出来：应扣与实扣不一致（失败）时，玩家必须能看见「没扣」这件事
    staminaText: `体力 应扣 ${resp.staminaCost} / 实扣 ${resp.staminaCharged}`,
    refunded,
    reportId: resp.reportId,
  }
}

/**
 * 组装一次批量扫荡的结算摘要。
 *
 * @param requested 客户端请求的次数。<b>必须由调用方给</b>：SweepResp 里没有这个字段，
 *                  而没有它就无法说明「为什么只扫了 7 次」—— 服务端在体力不足时
 *                  照实返回 executed 而不是报错（已经扫了的几次必须给奖励），
 *                  解释的责任就落在客户端
 */
export function buildSweepSummary(resp: SweepResp, requested: number): SweepSummary {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  if (!Number.isInteger(requested) || requested < 1) {
    // 只校验「是正整数」：1~10 的上界属于协议与服务端的约束（超过直接拒绝而不是截断），
    // 在展示层再钉一遍的话，配置一改就变成客户端先拒绝，玩家连请求都发不出去
    throw new Error(`requested 必须是正整数，实际=${requested}`)
  }
  const short = requested - resp.executed
  return {
    executedText: `实际扫荡 ${resp.executed} / ${requested} 次`,
    shortfallText: short > 0
      ? `少扫 ${short} 次：体力不足。已执行的 ${resp.executed} 次奖励照常发放`
      : null,
    rewardLines: resp.totalRewards.map((reward) => `${reward.name} ×${reward.count}`),
    staminaText: `体力 应扣 ${resp.staminaCost} / 实扣 ${resp.staminaCharged}`,
    reportIds: resp.results.map((result) => result.reportId),
  }
}

/** 三星条件的逐条文案（用于挑战结算，此时三个布尔值来自本次战斗而非历史最好）。 */
export function starConditionText(cleared: boolean, noLoss: boolean, withinRounds: boolean): string {
  return [
    cleared ? '✓通关' : '✗通关',
    noLoss ? '✓无损' : '✗无损',
    withinRounds ? '✓限时' : '✗限时',
  ].join(' ')
}
