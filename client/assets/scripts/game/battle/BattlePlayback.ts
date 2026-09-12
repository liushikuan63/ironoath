/**
 * 职责：战斗回放的时间轴控制（B05 §三：客户端播放服务端下发的快照，不实现 simulate）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>这个模块一行战斗数值都不算</b>。B05 的头号禁止项是「不要在客户端实现 simulate()」
 * （B00 跨语言一致性策略第 1 条）：客户端算一遍就必然与服务端分叉，
 * 而分叉的表现是「我看到的战报和别人的不一样」，那是不可能靠测试发现的 bug。
 * 所以本模块只做三件事：把快照切成播放步骤、按倍速换算时长、支持跳过。
 *
 * <p><b>为什么时间轴值得单独一个模块</b>：B05 要求「每回合 0.6s，8 回合 ≈ 5s，
 * 必须提供 1x / 2x / 跳过」。这三条里最容易做错的是「跳过」——
 * 把它实现成「把倍速调到很大」会让所有回合的动效同时启动，
 * 而 B05 明写每回合的播放必须是顺序的。跳过应当是「直接给出最终状态」，
 * 与倍速是两个正交的东西，本模块把它们分开建模。
 *
 * <p><b>战报重放 = 重播同一份快照</b>：同一份 BattleResultView 播两次，
 * 步骤序列与每步时长必须逐条相同（{@link #buildTimeline} 是纯函数，
 * 不读时钟、不读随机数）。这条性质让「战斗中杀进程重进 → 拉战报重播」
 * 与「当时看到的」一致（B05 验收 9）。
 */

import type { BattleResultView, RoundView } from '../../net/generated/BattleProtocol'

/** 播放倍速。取值来自 global.BATTLE_PLAYBACK_SPEEDS（"1,2"）。 */
export type PlaybackSpeed = 1 | 2

/** 一个播放步骤的类型。 */
export type StepKind =
  | 'opening'      // 开场：双方阵容亮相
  | 'round'        // 一回合的攻防与损失
  | 'skill'        // 回合内的一次技能触发（单独成步，否则技能特效会被回合动效盖掉）
  | 'settlement'   // 结算：胜负、死伤、掠夺

/** 一个播放步骤。 */
export interface PlaybackStep {
  readonly kind: StepKind
  /** 属于第几回合；opening 与 settlement 为 0 */
  readonly round: number
  /** 该步骤的时长（毫秒），已按倍速换算 */
  readonly durationMs: number
  /** round 步骤带上快照本身，播放方不必再回查 */
  readonly snapshot?: RoundView
}

export interface PlaybackOptions {
  /** 单回合基础时长（毫秒）。来源：global.BATTLE_ROUND_DISPLAY_MS */
  readonly roundMs: number
  /** 开场时长（毫秒）。来源：表现层约定，与回合时长同量级 */
  readonly openingMs: number
  /** 结算时长（毫秒） */
  readonly settlementMs: number
  /** 单次技能特效时长（毫秒） */
  readonly skillMs: number
  /** 允许的倍速档位。来源：global.BATTLE_PLAYBACK_SPEEDS */
  readonly speeds: readonly PlaybackSpeed[]
}

export class BattlePlayback {
  private readonly result: BattleResultView
  private readonly options: PlaybackOptions
  private speed: PlaybackSpeed = 1
  private skipped = false

  constructor(result: BattleResultView, options: PlaybackOptions) {
    if (result === undefined || result === null) {
      throw new Error('result 不得为空：客户端只播放服务端下发的战报，没有战报就没有可播的东西')
    }
    if (options.roundMs <= 0) {
      throw new Error(`roundMs 必须为正：${options.roundMs}`)
    }
    if (options.openingMs < 0 || options.settlementMs < 0 || options.skillMs < 0) {
      throw new Error('各段时长不得为负')
    }
    if (options.speeds.length === 0) {
      throw new Error('speeds 不得为空，否则没有任何可播的倍速')
    }
    if (result.rounds.length !== result.totalRounds) {
      // 快照数量与声称的回合数不符，说明战报在传输或存储中被截断了。
      // 这时候继续播会让玩家看到一个「打到第 5 回合就赢了」的错误结论
      throw new Error(`战报不完整：totalRounds=${result.totalRounds} 但只有 ${result.rounds.length} 个快照`)
    }
    this.result = result
    this.options = options
  }

  /** 当前倍速。 */
  get currentSpeed(): PlaybackSpeed {
    return this.speed
  }

  /**
   * 切换倍速。
   *
   * @returns 切换后的倍速；请求的档位不在配置允许范围内时保持原值并返回它
   *          （不抛异常：倍速是体验选项，玩家点错不该看到报错）
   */
  setSpeed(speed: PlaybackSpeed): PlaybackSpeed {
    if (!this.options.speeds.includes(speed)) {
      console.warn(`[BattlePlayback] 倍速 ${speed} 不在配置允许的档位 `
        + `${JSON.stringify(this.options.speeds)} 内，保持 ${this.speed}x`)
      return this.speed
    }
    this.speed = speed
    return this.speed
  }

  /**
   * 跳过：直接给出最终状态。
   *
   * <p><b>跳过不是「把倍速调到很大」</b>：那会让所有回合的动效同时启动，
   * 而 B05 要求每回合顺序播放。跳过是「不播中间过程，直接进结算」。
   */
  skip(): void {
    this.skipped = true
  }

  get isSkipped(): boolean {
    return this.skipped
  }

  /**
   * 生成完整的播放时间轴。
   *
   * <p><b>纯函数</b>：不读时钟、不读随机数、不受当前倍速以外的状态影响。
   * 同一份战报 + 同一倍速 ⇒ 逐条相同的步骤序列与时长。
   * 这条性质是 B05 验收 9（杀进程重进后重播结果与原战报一致）的前提。
   *
   * <p>跳过后只返回结算那一步。
   */
  buildTimeline(): readonly PlaybackStep[] {
    const steps: PlaybackStep[] = []
    if (this.skipped) {
      steps.push({ kind: 'settlement', round: 0, durationMs: 0 })
      return steps
    }
    const scale = (ms: number): number => Math.round(ms / this.speed)

    steps.push({ kind: 'opening', round: 0, durationMs: scale(this.options.openingMs) })
    for (const round of this.result.rounds) {
      // 技能单独成步并排在回合步骤之前：技能是这一回合的「因」，
      // 损失是「果」，先播因再播果玩家才看得懂为什么这一回合掉了这么多兵
      for (const skill of round.skills) {
        steps.push({ kind: 'skill', round: round.round, durationMs: scale(this.options.skillMs) })
        // skill 变量只用于计数，但保留遍历而不是用 round.skills.length，
        // 是为了将来能在步骤里带上具体是哪条技能（当前协议已经带了 skillId）
        void skill
      }
      steps.push({ kind: 'round', round: round.round, durationMs: scale(this.options.roundMs), snapshot: round })
    }
    steps.push({ kind: 'settlement', round: 0, durationMs: scale(this.options.settlementMs) })
    return steps
  }

  /** 总播放时长（毫秒）。用于进度条与「还剩多久」的提示。 */
  totalDurationMs(): number {
    let total = 0
    for (const step of this.buildTimeline()) {
      total += step.durationMs
    }
    return total
  }

  /** 战报里出现的技能触发总数，供战报 UI 的分回合折叠列表使用。 */
  skillCount(): number {
    let total = 0
    for (const round of this.result.rounds) {
      total += round.skills.length
    }
    return total
  }

  /**
   * 医院溢出死亡的总数（双方合计）。
   *
   * <p>B05 验收 7 要求「医院溢出：死亡数量与 UI 提示完全吻合」。
   * overflowDead 必须单独提示而不是并进总死亡里 —— 否则玩家看到「阵亡 500」
   * 却不知道其中有 300 是医院不够导致的，也就没有升级医院的动机，
   * 而医院正是 B05 伤兵规则里唯一的那个决策点。
   */
  overflowDead(): { attacker: number; defender: number; total: number } {
    const attacker = this.result.attackerOverflowDead
    const defender = this.result.defenderOverflowDead
    return { attacker, defender, total: attacker + defender }
  }
}

/**
 * 从 global.BATTLE_PLAYBACK_SPEEDS（形如 "1,2"）解析允许的倍速档位。
 *
 * <p>放在这里而不是让调用方自己 split：解析规则只有一份，
 * 否则「配置里加了 4x 但某个界面不认识」这种分叉迟早会出现。
 */
export function parseSpeeds(raw: string): readonly PlaybackSpeed[] {
  const out: PlaybackSpeed[] = []
  for (const part of raw.split(',')) {
    const trimmed = part.trim()
    if (trimmed.length === 0) {
      continue
    }
    const value = Number(trimmed)
    if (value !== 1 && value !== 2) {
      // 不认识的档位直接拒绝，而不是「向下取到最近的合法值」：
      // 配置里写了 4 就说明有人想加 4x，静默改成 2x 会让那次改动看起来生效了却没生效
      throw new Error(`BATTLE_PLAYBACK_SPEEDS 含不支持的倍速 "${trimmed}"（只支持 1 与 2）。`
        + '要加新档位必须先在这里加，否则播放时长会算错')
    }
    if (!out.includes(value)) {
      out.push(value)
    }
  }
  if (out.length === 0) {
    throw new Error(`BATTLE_PLAYBACK_SPEEDS 解析后为空，原始值="${raw}"`)
  }
  return out
}
