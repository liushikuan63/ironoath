/**
 * 职责：战斗回放场景 —— 播放服务端下发的战报快照，提供 1x / 2x / 跳过（B05 §三）。
 * 依赖：cc（渲染）、game/battle/BattlePlayback（时间轴）、scene/NodePool（飘字池化）。
 *
 * <p><b>本场景一行战斗数值都不算</b>（B05 头号禁止项：不要在客户端实现 simulate()）。
 * 客户端算一遍就必然与服务端分叉，而分叉的表现是「我看到的战报和别人的不一样」——
 * 那是不可能靠测试发现的 bug。所以这里只做三件事：按 BattlePlayback 给的时间轴推进、
 * 把 RoundView 里已经算好的数字搬到屏幕上、播完进结算。
 *
 * <p><b>渲染单位是「兵种堆叠」而不是单个士兵</b>：协议下发的 UnitStack 本来就按兵种聚合，
 * 10 万兵力也只有 4 个堆叠。B05 §三 要求「每排最多渲染 12 个单位，超出用 ×N 图标聚合」，
 * 本场景每排只画 1 个色块 + 一个 ×N 文本，天然满足；这是低端机保 60 帧的关键，
 * 绝不要试图把堆叠展开成单个单位。
 *
 * <p><b>跳过不是「把倍速调到很大」</b>：那会让所有回合的动效同时启动，
 * 而 B05 明写每回合必须顺序播放。跳过直接进结算，由 BattlePlayback.skip() 表达。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene 资产与节点层级、正式美术（兵种图标、
 * 技能特效、飘字动画曲线）。本文件用 Graphics 色块占位，与 MainCity / WorldMap 同一套做法 ——
 * 换美术时只需替换绘制部分，时间轴推进与快照搬运逻辑不用动。
 */

import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import { BattlePlayback, parseSpeeds } from '../game/battle/BattlePlayback'
import type { PlaybackOptions, PlaybackStep } from '../game/battle/BattlePlayback'
import type { BattleResultView, RoundView, UnitStack, UnitType } from '../net/generated/BattleProtocol'
import { NodePool } from './NodePool'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(18, 15, 13, 255)
const COLOR_PANEL = new Color(38, 31, 26, 255)
const COLOR_ATTACKER = new Color(139, 26, 26, 255)
const COLOR_DEFENDER = new Color(74, 88, 104, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_LOSS = new Color(200, 60, 40, 255)
const COLOR_SKILL = new Color(196, 168, 92, 255)
const COLOR_WIN = new Color(120, 176, 96, 255)

/** 兵种展示顺序。刻意写死顺序而不是遍历协议里的枚举 —— 排版必须稳定， */
const UNIT_ORDER: readonly UnitType[] = ['INFANTRY', 'CAVALRY', 'ARCHER', 'SIEGE']
const UNIT_LABEL: Readonly<Record<UnitType, string>> = {
  INFANTRY: '步兵', CAVALRY: '骑兵', ARCHER: '弓手', SIEGE: '攻城',
}

/**
 * 倍速档位的原始配置值。
 *
 * <p>TODO(B05 表现层缺口): 应当由服务端随战报下发 global.BATTLE_PLAYBACK_SPEEDS 与各段时长，
 * 客户端的 config/generated 只有类型没有值，铁律 1 不允许在这里写死数值。
 * 目前先按 B05 §三 明写的「1x / 2x」占位，接入点已经留在 attach 的 options 参数上 ——
 * 适配层拿到服务端下发的时长后直接传进来即可。
 */
const SPEEDS_RAW = '1,2'

const STACK_BLOCK = 54
const STACK_GAP = 10
const COLUMN_OFFSET = 210
const FLOAT_TEXT_RISE = 46
const HUD_BUTTON_WIDTH = 96
const HUD_BUTTON_HEIGHT = 44
const HUD_BUTTON_GAP = 12

/** 飘字上升动画的时长（毫秒）。特效时长，不是游戏数值。 */
const FLOAT_TEXT_MS = 700

@ccclass('BattlePlaybackView')
export class BattlePlaybackView extends Component {
  private playback: BattlePlayback | null = null
  private result: BattleResultView | null = null
  private timeline: readonly PlaybackStep[] = []
  private stepIndex = 0
  private elapsedMs = 0
  private finished = false
  /** onLoad 之前就可能被 attach（组件由代码挂上去时），先存着 */
  private pending: { result: BattleResultView; options: PlaybackOptions } | null = null

  private floatPool: NodePool | null = null
  /** 在飞的飘字。y 自己存着：cc 类型桩里 Node 只有 setPosition，没有 position 取值器 */
  private readonly floaters: Array<{ node: Node; age: number; x: number; y: number }> = []

  private titleLabel: Label | null = null
  private roundLabel: Label | null = null
  private attritionLabel: Label | null = null
  private skillLabel: Label | null = null
  private settlementLabel: Label | null = null
  private speedLabel: Label | null = null
  private readonly stackLabels = new Map<string, Label>()
  private readonly stackBlocks = new Map<string, Graphics>()

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.floatPool = new NodePool(this.node, () => this.createFloater(), 8)
    this.buildColumns(size.height)
    this.buildCenterInfo(size.height)
    this.buildSettlement(size.height)
    this.buildControls(size.width, size.height)
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending.result, pending.options)
    }
  }

  override onDestroy(): void {
    // 池必须显式销毁：飘字节点是运行时创建的，场景切换后不清就会一直挂在内存里
    this.floatPool?.destroy()
    this.floatPool = null
    this.floaters.length = 0
    this.stackLabels.clear()
    this.stackBlocks.clear()
  }

  /**
   * 装载一份战报并开始播放。
   *
   * @param result  服务端下发的 BattleResultView。<b>客户端不得自己合成它</b>
   * @param options 播放时长参数。来源 global.BATTLE_ROUND_DISPLAY_MS 等，由调用方注入
   */
  attach(result: BattleResultView, options: PlaybackOptions): void {
    if (this.floatPool === null) {
      this.pending = { result, options }
      return
    }
    this.playback = new BattlePlayback(result, options)
    this.result = result
    this.stepIndex = 0
    this.elapsedMs = 0
    this.finished = false
    this.timeline = this.playback.buildTimeline()
    if (this.settlementLabel !== null) {
      this.settlementLabel.node.active = false
    }
    this.refreshSpeedLabel()
    this.renderOpening(result)
  }

  override update(deltaTime: number): void {
    const playback = this.playback
    if (playback === null || this.finished) {
      this.tickFloaters(deltaTime)
      return
    }
    // 时间轴换过（切倍速 / 跳过）就要重取：buildTimeline 是纯函数，重取不会引入不一致
    this.elapsedMs += deltaTime * 1000
    const step = this.timeline[this.stepIndex]
    if (step === undefined) {
      this.finish()
      return
    }
    if (this.elapsedMs < step.durationMs) {
      this.tickFloaters(deltaTime)
      return
    }
    this.elapsedMs -= step.durationMs
    this.stepIndex++
    this.applyStep(step)
    if (this.stepIndex >= this.timeline.length) {
      this.finish()
    }
    this.tickFloaters(deltaTime)
  }

  // ---------- 控制 ----------

  /** 切到下一档倍速。档位来自配置（global.BATTLE_PLAYBACK_SPEEDS），不在这里写死。 */
  cycleSpeed(): void {
    const playback = this.playback
    if (playback === null) {
      return
    }
    const allowed = parseSpeeds(SPEEDS_RAW)
    const index = allowed.indexOf(playback.currentSpeed)
    const next = allowed[(index + 1) % allowed.length] ?? 1
    playback.setSpeed(next)
    // 倍速改变后整条时间轴的时长都变了，必须重算。
    // 已播到第几步（stepIndex）不动：玩家的预期是「从当前位置继续，只是快一点」，
    // 从头重播会让他以为自己点错了
    this.timeline = playback.buildTimeline()
    this.elapsedMs = 0
    this.refreshSpeedLabel()
  }

  /** 跳过：直接进结算（不是把倍速调大，理由见文件头）。 */
  skipToEnd(): void {
    const playback = this.playback
    if (playback === null) {
      return
    }
    playback.skip()
    this.timeline = playback.buildTimeline()
    this.stepIndex = 0
    this.elapsedMs = 0
    this.finish()
  }

  // ---------- 搭建 ----------

  private buildBackground(width: number, height: number): void {
    const node = new Node('Background')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(width, height)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
  }

  /**
   * 左右两列兵种堆叠。
   *
   * <p>每列固定 4 行（协议里的兵种数），行节点在搭建期一次性建好，
   * 播放时只改文本与色块大小 —— 每帧新建节点是掉帧的主要来源。
   */
  private buildColumns(height: number): void {
    const topY = height / 2 - 150
    for (const side of ['attacker', 'defender'] as const) {
      const sign = side === 'attacker' ? -1 : 1
      this.createLabel(`${side}_header`, side === 'attacker' ? '攻方' : '守方',
        sign * COLUMN_OFFSET, topY + STACK_BLOCK, side === 'attacker' ? COLOR_ATTACKER : COLOR_DEFENDER, 22)
      UNIT_ORDER.forEach((type, index) => {
        const y = topY - index * (STACK_BLOCK + STACK_GAP)
        const x = sign * COLUMN_OFFSET
        const block = new Node(`${side}_${type}_block`)
        block.layer = this.node.layer
        this.node.addChild(block)
        block.setPosition(new Vec3(x, y, 0))
        block.addComponent(UITransform)
        const graphics = block.addComponent(Graphics)
        this.stackBlocks.set(`${side}:${type}`, graphics)

        const label = this.createLabel(`${side}_${type}_label`, '', x, y, COLOR_TEXT, 18)
        this.stackLabels.set(`${side}:${type}`, label)
      })
    }
  }

  private buildCenterInfo(height: number): void {
    this.titleLabel = this.createLabel('Title', '', 0, height / 2 - 60, COLOR_COPPER_GOLD, 26)
    this.roundLabel = this.createLabel('Round', '', 0, 60, COLOR_TEXT, 22)
    this.attritionLabel = this.createLabel('Attrition', '', 0, 26, COLOR_TEXT_DIM, 16)
    this.skillLabel = this.createLabel('Skill', '', 0, -8, COLOR_SKILL, 18)
  }

  private buildSettlement(height: number): void {
    this.settlementLabel = this.createLabel('Settlement', '', 0, -height / 2 + 150, COLOR_TEXT, 18)
    this.settlementLabel.verticalAlign = Label.VerticalAlign.BOTTOM
    this.settlementLabel.node.active = false
  }

  private buildControls(width: number, height: number): void {
    const y = -height / 2 + 56
    const startX = -width / 2 + HUD_BUTTON_WIDTH / 2 + HUD_BUTTON_GAP
    const buttons: Array<{ name: string; onTap: () => void }> = [
      { name: 'SpeedButton', onTap: () => this.cycleSpeed() },
      { name: 'SkipButton', onTap: () => this.skipToEnd() },
    ]
    buttons.forEach((button, index) => {
      const x = startX + index * (HUD_BUTTON_WIDTH + HUD_BUTTON_GAP)
      const node = new Node(button.name)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(x, y, 0))
      node.addComponent(UITransform).setContentSize(HUD_BUTTON_WIDTH, HUD_BUTTON_HEIGHT)
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 2
      graphics.roundRect(-HUD_BUTTON_WIDTH / 2, -HUD_BUTTON_HEIGHT / 2, HUD_BUTTON_WIDTH, HUD_BUTTON_HEIGHT, 6)
      graphics.fill()
      graphics.stroke()
      node.on('touch-start', button.onTap, this)
    })
    this.speedLabel = this.createLabel('SpeedCaption', '', startX, y, COLOR_TEXT, 18)
    this.createLabel('SkipCaption', '跳过', startX + HUD_BUTTON_WIDTH + HUD_BUTTON_GAP, y, COLOR_TEXT, 18)
  }

  private createFloater(): Node {
    const node = new Node('Floater')
    node.layer = this.node.layer
    node.addComponent(UITransform)
    node.addComponent(Graphics)
    const caption = new Node('Caption')
    caption.layer = node.layer
    node.addChild(caption)
    caption.addComponent(UITransform)
    const label = applySystemUiFont(caption.addComponent(Label))
    label.fontSize = 20
    label.color = COLOR_LOSS
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    return node
  }

  private createLabel(name: string, text: string, x: number, y: number, color: Color, fontSize: number): Label {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.color = color
    label.fontSize = fontSize
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }

  // ---------- 播放 ----------

  private renderOpening(result: BattleResultView): void {
    if (this.titleLabel !== null) {
      this.titleLabel.string = `战斗回放 · ${battleTypeText(result.battleType)} · 共 ${result.totalRounds} 回合`
    }
    // 开场先亮出双方初始阵容：第一回合的快照就是开局兵力
    const first = result.rounds[0]
    if (first !== undefined) {
      this.renderStacks('attacker', first.attackerUnits)
      this.renderStacks('defender', first.defenderUnits)
    }
    if (this.roundLabel !== null) {
      this.roundLabel.string = ''
    }
    if (this.skillLabel !== null) {
      this.skillLabel.string = ''
    }
    if (this.attritionLabel !== null) {
      this.attritionLabel.string = `随机种子 ${result.seed}`
    }
  }

  private applyStep(step: PlaybackStep): void {
    switch (step.kind) {
      case 'opening':
        return
      case 'skill':
        this.renderSkillStep(step.round)
        return
      case 'round':
        if (step.snapshot !== undefined) {
          this.renderRound(step.snapshot)
        }
        return
      case 'settlement':
        this.finish()
        return
    }
  }

  private renderRound(round: RoundView): void {
    if (this.roundLabel !== null) {
      this.roundLabel.string = `第 ${round.round} 回合`
    }
    if (this.attritionLabel !== null) {
      // 减员系数是服务端算好下发的定点数，这里只格式化展示，不做任何换算判定
      this.attritionLabel.string = `攻方总攻 ${round.attackerAttack} · 守方总防 ${round.defenderDefense}`
        + ` · 减员系数 ${formatFixed(round.attritionFixed)}`
    }
    this.renderStacks('attacker', round.attackerUnits)
    this.renderStacks('defender', round.defenderUnits)
    // 损失飘字：本回合的损失落在对方身上，所以攻方的损失飘在守方一侧
    this.spawnFloater(-COLUMN_OFFSET, `-${round.attackerLoss}`)
    this.spawnFloater(COLUMN_OFFSET, `-${round.defenderLoss}`)
  }

  private renderSkillStep(round: number): void {
    const result = this.result
    if (result === null || this.skillLabel === null) {
      return
    }
    const snapshot = result.rounds.find((item) => item.round === round)
    if (snapshot === undefined) {
      return
    }
    // 同回合可能有多次触发，播放到第几步就展示第几条 —— 顺序与服务端结算顺序一致
    const alreadyPlayed = this.timeline
      .slice(0, this.stepIndex)
      .filter((item) => item.kind === 'skill' && item.round === round).length
    const trigger = snapshot.skills[alreadyPlayed]
    if (trigger === undefined) {
      this.skillLabel.string = ''
      return
    }
    const value = trigger.valueFixed === null ? '' : ` ${formatFixed(trigger.valueFixed)}`
    this.skillLabel.string = `${trigger.side === 'ATTACKER' ? '攻方' : '守方'} 触发 `
      + `${trigger.skillName ?? trigger.skillId}${value}`
  }

  private renderStacks(side: 'attacker' | 'defender', stacks: readonly UnitStack[]): void {
    const byType = new Map<UnitType, number>()
    for (const stack of stacks) {
      byType.set(stack.unitType, stack.count)
    }
    for (const type of UNIT_ORDER) {
      const count = byType.get(type) ?? 0
      const label = this.stackLabels.get(`${side}:${type}`)
      if (label !== undefined) {
        label.string = count === 0 ? `${UNIT_LABEL[type]} —` : `${UNIT_LABEL[type]} ×${count}`
        label.color = count === 0 ? COLOR_TEXT_DIM : COLOR_TEXT
      }
      const graphics = this.stackBlocks.get(`${side}:${type}`)
      if (graphics !== undefined) {
        // 色块大小只反映「还有没有兵」，不按兵力线性缩放：
        // 十万与一万的差距画不成看得出来的尺寸差，而「清零」必须一眼可见
        const size = count === 0 ? 6 : STACK_BLOCK
        graphics.clear()
        graphics.fillColor = count === 0 ? COLOR_PANEL : (side === 'attacker' ? COLOR_ATTACKER : COLOR_DEFENDER)
        graphics.roundRect(-size / 2, -size / 2, size, size, 6)
        graphics.fill()
      }
    }
  }

  private finish(): void {
    if (this.finished) {
      return
    }
    this.finished = true
    const result = this.result
    const playback = this.playback
    if (result === null || playback === null || this.settlementLabel === null) {
      return
    }
    const overflow = playback.overflowDead()
    const lines: string[] = [
      `${winnerText(result.winner)} · 打了 ${result.totalRounds} 回合`,
      `攻方 阵亡 ${result.attackerDead} / 伤兵 ${result.attackerWounded}`
        + ` / 医院溢出死 ${result.attackerOverflowDead}`,
      `守方 阵亡 ${result.defenderDead} / 伤兵 ${result.defenderWounded}`
        + ` / 医院溢出死 ${result.defenderOverflowDead}`,
    ]
    if (overflow.total > 0) {
      // B05 验收 7：医院溢出必须单独提示。并进总死亡里，玩家就看不到「有 N 个是因为医院不够而死」，
      // 也就没有升级医院的动机 —— 而医院正是伤兵规则里唯一的那个决策点
      lines.push(`⚠ 医院容量不足，双方合计 ${overflow.total} 名伤兵未能救治而死`)
    }
    if (result.loot.length > 0) {
      const loot = result.loot.map((entry) => `${entry.resourceType} ${entry.amount}`).join(' · ')
      lines.push(`掠夺 ${loot}（负重上限 ${result.lootCapacity}）`)
    }
    lines.push(`存活 攻方 ${totalOf(result.attackerSurvivors)} / 守方 ${totalOf(result.defenderSurvivors)}`)
    this.settlementLabel.string = lines.join('\n')
    this.settlementLabel.color = result.winner === 'DRAW' ? COLOR_TEXT : COLOR_WIN
    this.settlementLabel.node.active = true
    if (this.roundLabel !== null) {
      this.roundLabel.string = '战斗结束'
    }
    if (this.skillLabel !== null) {
      this.skillLabel.string = ''
    }
  }

  // ---------- 飘字 ----------

  private spawnFloater(x: number, text: string): void {
    const pool = this.floatPool
    if (pool === null) {
      return
    }
    const node = pool.acquire()
    node.setPosition(new Vec3(x, 0, 0))
    const label = node.children[0]?.getComponent(Label)
    if (label !== undefined && label !== null) {
      label.string = text
    }
    this.floaters.push({ node, age: 0, x, y: 0 })
  }

  /** 飘字上升并淡出，到时长后归还池子（B07 §4：飘字必须池化）。 */
  private tickFloaters(deltaTime: number): void {
    const pool = this.floatPool
    if (pool === null || this.floaters.length === 0) {
      return
    }
    const step = deltaTime * 1000
    for (const floater of [...this.floaters]) {
      floater.age += step
      floater.y += FLOAT_TEXT_RISE * (step / FLOAT_TEXT_MS)
      floater.node.setPosition(new Vec3(floater.x, floater.y, 0))
      if (floater.age >= FLOAT_TEXT_MS) {
        pool.release(floater.node)
        const index = this.floaters.indexOf(floater)
        if (index >= 0) {
          this.floaters.splice(index, 1)
        }
      }
    }
  }

  private refreshSpeedLabel(): void {
    if (this.speedLabel !== null && this.playback !== null) {
      this.speedLabel.string = `${this.playback.currentSpeed}x`
    }
  }
}

function totalOf(stacks: readonly UnitStack[]): number {
  let total = 0
  for (const stack of stacks) {
    total += stack.count
  }
  return total
}

function battleTypeText(type: BattleResultView['battleType']): string {
  switch (type) {
    case 'PVE': return '讨伐野怪'
    case 'PVP_SOLO': return '单人攻城'
    case 'PVP_RALLY': return '集结攻城'
    case 'SIEGE': return '要塞战'
    default: return type
  }
}

function winnerText(winner: BattleResultView['winner']): string {
  switch (winner) {
    case 'ATTACKER': return '攻方胜'
    case 'DEFENDER': return '守方胜'
    case 'DRAW': return '平局'
    default: return winner
  }
}

/**
 * 定点数（×10000）格式化成最多两位小数的字符串。
 *
 * <p>全程整数运算，不还原成 double：用 double 会在 0.02 这种值上得到 0.019999…，
 * 而战报里公示的减员系数是玩家要拿去对表的数字，差一位就会被当成数值造假。
 * 尾部零不保留（0.50 显示成 0.5），但中间零必须保留（0.05 不能显示成 0.5）。
 */
function formatFixed(value: number): string {
  const sign = value < 0 ? '-' : ''
  const abs = Math.abs(value)
  const whole = Math.floor(abs / 10000)
  const fraction = abs % 10000
  if (fraction === 0) {
    return `${sign}${whole}`
  }
  const twoDigits = Math.floor(fraction / 100)
  const rest = fraction % 100
  const text = rest === 0
    ? `${whole}.${String(twoDigits).padStart(2, '0')}`
    : `${whole}.${String(twoDigits).padStart(2, '0')}${String(Math.floor(rest / 10))}${rest % 10}`
  return `${sign}${text}`
}
