/**
 * 职责：新手引导的**通用驱动器**（B18 §一.3）—— 吃什么脚本、什么时候弹、玩家点了算什么。
 * 依赖：只依赖生成的协议类型（`GuideProtocol`），不 import 'cc'。
 *
 * <p><b>本文件里没有任何一步的具体内容</b>：没有文案、没有步骤名、没有"第几步做什么"。
 * 引导逻辑硬编码进 UI 是 B12 的禁止项，而"客户端全仓搜不到步骤文案"（验收 1）要的就是这一点。
 *
 * <p><b>三条贯穿本文件的纪律</b>：
 * <ol>
 *   <li><b>位置只由服务端决定</b>：{@link GuideDriver.applyProgress} 只接受响应里的
 *       {@code nextStepIndex}，本模块**绝不自己加一**。自己加一的结局是"玩家点了但服务端没认"
 *       与"界面已经走到第 4 步、服务端还在第 3 步"这种分叉，而修法只能靠重开。</li>
 *   <li><b>不判完成</b>：客户端只有上报权（`COMPLETE` 的意思是"请检查我"）。
 *       判据 `judge` 连协议都没有，正是为了让人没法在这里"顺手"判。</li>
 *   <li><b>遮罩吃触摸，乱点不卡死</b>：高亮外的点击按忽略处理并记一次埋点，
 *       频率上限**每一步一次**（不按次数：那等于给刷埋点开一个口子）。</li>
 * </ol>
 *
 * <p>场景层（`scene/GuideView.ts`）只负责把 {@link GuideDriver.frameFor} 画出来并把触摸事件交回本模块。
 */

import type { GuideScriptResp, GuideStepView } from '../../net/generated/GuideProtocol'

/** 遮罩几何：`full` 或 `x,y,w,h`。解析失败一律退回整屏 —— 宁可暗一点，也不要"引导挡不住"。 */
export interface GuideMask {
  readonly full: boolean
  readonly rect: readonly [number, number, number, number] | null
}

/** 一帧要画的东西。全部字段都来自服务端下发的这一步，客户端一个字都不补。 */
export interface GuideFrame {
  readonly step: GuideStepView
  readonly mask: GuideMask
  /** 「第 N/M 步」里的 N 与 M —— 数字来自下发的 stepIndex 与 steps.length，不自己数。 */
  readonly position: { readonly index: number; readonly total: number }
  /** 跳过按钮只在 `skippable=true` 时出现（B18 验收 8 的界面那一半）。 */
  readonly showSkip: boolean
}

/** 埋点出口：`enter` / `complete` / `skip` 三个动作（B18 §一.4）。 */
export type GuideTrack = (action: 'enter' | 'complete' | 'skip' | 'outside_tap', stepId: string) => void

/**
 * 解析遮罩。`x,y,w,h` 要求四个非负整数：填错（少一位、带空格、写负数）不猜、不局部生效，
 * 直接退回整屏 —— 遮罩的意义是"挡住别的输入"，半生效比不生效更糟。
 */
export function parseGuideMask(area: string): GuideMask {
  if (area === 'full') {
    return { full: true, rect: null }
  }
  const parts = area.split(',')
  if (parts.length !== 4) {
    return { full: true, rect: null }
  }
  const [x = -1, y = -1, w = -1, h = -1] = parts.map(part => Number(part.trim()))
  if (![x, y, w, h].every(n => Number.isFinite(n) && n >= 0)) {
    return { full: true, rect: null }
  }
  return { full: false, rect: [x, y, w, h] }
}

/**
 * 把一次下发变成可画的状态。
 *
 * <p>{@code null} 表示"这一号不该看引导"（老号、或已经走完）—— 由服务端的 `applies` 与
 * `nextStepIndex` 共同决定，客户端不按等级自己判（判一次就多一个家）。
 */
export class GuideDriver {
  private readonly steps: readonly GuideStepView[]
  private readonly version: string
  private nextIndex: number | null
  private shown = false
  private outsideTapLogged = false

  private constructor(resp: GuideScriptResp) {
    this.steps = resp.steps
    this.version = resp.version
    this.nextIndex = resp.nextStepIndex
  }

  /** 服务端说该看、且还有下一步要做，才起一个驱动器。 */
  static from(resp: GuideScriptResp): GuideDriver | null {
    if (!resp.applies || resp.nextStepIndex === null || resp.steps.length === 0) {
      return null
    }
    return new GuideDriver(resp)
  }

  get scriptVersion(): string {
    return this.version
  }

  /** 当前这一步；越界（脚本被热更短了）返回 null，等于本帧不画。 */
  current(): GuideStepView | null {
    if (this.nextIndex === null) {
      return null
    }
    return this.steps.find(step => step.stepIndex === this.nextIndex) ?? null
  }

  /** 已结束（走完或被跳完）：场景层据此摘掉遮罩。 */
  get finished(): boolean {
    return this.nextIndex === null
  }

  /**
   * 现在该不该画、画什么。
   *
   * @param openPanelKey 当前打开的面板 key（`PanelNav.current()`）。`STATE_REACHED` 那一步不看它 ——
   *                     那一步的语义就是"别等面板"。
   */
  frameFor(openPanelKey: string | null): GuideFrame | null {
    const step = this.current()
    if (step === null) {
      return null
    }
    if (step.trigger === 'PANEL_OPEN' && step.panelKey !== null && step.panelKey !== openPanelKey) {
      return null
    }
    return {
      step,
      mask: parseGuideMask(step.maskArea),
      position: { index: step.stepIndex, total: this.steps.length },
      showSkip: step.skippable,
    }
  }

  /** 首次弹出时记一次 enter（一个驱动实例内一步只记一次，重复切面板不该再算一次进入）。 */
  markShown(track: GuideTrack): void {
    const step = this.current()
    if (step === null || this.shown) {
      return
    }
    this.shown = true
    track('enter', step.id)
  }

  /** 上报"做完了"。真正的推进等 {@link applyProgress}，这里只负责埋点与把 id 交出去。 */
  completedAction(track: GuideTrack): { readonly stepId: string } | null {
    const step = this.current()
    if (step === null) {
      return null
    }
    track('complete', step.id)
    return { stepId: step.id }
  }

  /** 上报"跳过"。不可跳的步界面本就没有这个按钮，走到这里说明调用方画错了。 */
  skipAction(track: GuideTrack): { readonly stepId: string } | null {
    const step = this.current()
    if (step === null || !step.skippable) {
      return null
    }
    track('skip', step.id)
    return { stepId: step.id }
  }

  /**
   * 遮罩内、高亮外的点击：忽略，但每步允许记一次埋点（B18 §一.3 的"频率=1 次/步"）。
   * 不按次数记 —— 那等于给刷埋点开一个口子。
   */
  outsideTap(track: GuideTrack): void {
    const step = this.current()
    if (step === null || this.outsideTapLogged) {
      return
    }
    this.outsideTapLogged = true
    track('outside_tap', step.id)
  }

  /**
   * 消费一次上报结果。**位置只按服务端给的挪**。
   *
   * <p>`advanced=false` 且没结束时留在原步 —— 玩家点了"我做完了"而判据没成立是正常路径，
   * 界面不该消失、也不该报错（服务端为此刻意没有错误码）。
   */
  applyProgress(nextStepIndex: number | null): void {
    if (nextStepIndex === null) {
      this.nextIndex = null
      return
    }
    this.nextIndex = nextStepIndex
    this.shown = false
    this.outsideTapLogged = false
  }
}
