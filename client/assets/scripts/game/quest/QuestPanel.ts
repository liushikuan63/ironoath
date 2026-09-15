/**
 * 职责：任务面板的展示数据组装与「三选一送将」的选择流程（B12 §1，收口清单 #98）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：任务能不能领（`claimable`）、可不可以做（`locked`）、
 * 有没有候选（`heroChoices`）全部原样取自服务端。客户端**不自己算**「完成且未领」，
 * 也不自己判断「这个号是不是已经选过」—— 前置链与幂等都在服务端，算第二遍就是把口径搬到客户端，
 * 而两处哪天分叉的表现是「按钮亮着但点下去被拒」。
 *
 * <p><b>三选一为什么要单独一层流程而不是一个按钮</b>：带候选的任务不允许不选就领
 * （服务端会拒，而且刻意不替玩家挑）。所以界面上「领奖」这个动作对这类任务必须分成两步：
 * 先弹选择 → 选定后再发 claim。把这层流程写在这里而不是散在场景脚本里，是为了让它
 * 能被 node:test 直接驱动：断「没选不发请求」「选了才带 heroChoice」「无候选的任务不带」这三条。
 */

import type {
  HeroChoice,
  QuestClaimResp,
  QuestListResp,
  QuestReward,
  QuestView,
} from '../../net/generated/QuestProtocol'

/** 任务列表的一行。 */
export interface QuestRow {
  readonly questId: string
  /** 「每日 · 讨伐野怪 3 次」这样的标题。类型与名字都取自服务端，客户端不拼接分类 */
  readonly title: string
  /** 进度文本：「2/5」或「已完成」。状态型的进度会回落，所以按 current/goalValue 原样展示 */
  readonly progressText: string
  /** 状态文本：未解锁 / 可领取 / 已领取 / 进行中。四态由服务端三个布尔组合而来，本模块只翻译 */
  readonly statusText: string
  /** 未解锁原因（前置任务名），已解锁为 null。一个灰掉的行不说明为什么，玩家会以为是 bug */
  readonly lockedHint: string | null
  /** 能不能点「领取」。等于服务端的 claimable，不自己算 */
  readonly claimable: boolean
  /**
   * 可选武将列表（三选一那类奖励，id 与名字成对）。**空数组表示直接领**；
   * 非空时界面必须先让玩家选一个 —— 不选就发请求会被服务端拒。
   */
  readonly heroChoices: readonly HeroChoice[]
}

/** 整个任务列表页的数据。 */
export interface QuestListView {
  readonly rows: readonly QuestRow[]
  /** 「3 个奖励可领取」；没有可领时为 null */
  readonly claimableText: string | null
}

/**
 * 组装任务列表页。
 *
 * <p>行顺序照搬服务端（它按章节/类型稳定排序）：客户端再排一次的话，
 * 「每日任务在最前」这条规则就会有两份实现，而两份迟早不一致。
 */
export function buildQuestList(resp: QuestListResp): QuestListView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const namesById = new Map(resp.quests.map(q => [q.questId, q.name] as const))
  const rows = resp.quests.map(quest => toRow(namesById, quest))
  return {
    rows,
    claimableText: resp.claimableCount > 0 ? `${resp.claimableCount} 个奖励可领取` : null,
  }
}

/**
 * 前置任务的展示名。
 *
 * <p>名字取自**同一次响应里那条任务自己**（列表连未解锁的行一起下发，所以查得到），
 * 不是客户端去查配置表 —— 把 `quest_main_01` 这种内部编号印到玩家眼前，等于让玩家去猜表，
 * 而 #100 已经为同一件事定过口径：展示名由服务端下发，客户端不翻译。
 * 查不到时宁可只说「前置任务」，**绝不退回成 id**：少一句提示只是信息少，多一个编号是界面在说黑话。
 */
function prerequisiteName(quest: QuestView, namesById: ReadonlyMap<string, string>): string {
  if (quest.preQuestId === undefined || quest.preQuestId === null) {
    return '前置任务'
  }
  return namesById.get(quest.preQuestId) ?? '前置任务'
}

function toRow(namesById: ReadonlyMap<string, string>, quest: QuestView): QuestRow {
  return {
    questId: quest.questId,
    title: `${typeLabel(quest.type)} · ${quest.name}`,
    progressText: quest.complete
      ? '已完成'
      : `${formatProgress(quest.current)}/${formatProgress(quest.goalValue)}`,
    statusText: quest.claimed
      ? '已领取'
      : quest.locked
        ? '未解锁'
        : quest.claimable
          ? '可领取'
          : '进行中',
    lockedHint: quest.locked
      ? `完成后解锁：${prerequisiteName(quest, namesById)}`
      : null,
    claimable: quest.claimable,
    heroChoices: quest.heroChoices,
  }
}

/** 任务类型的展示名。取值来自协议枚举，未知值原样回落（新增类型时不该让整页崩掉）。 */
function typeLabel(type: string): string {
  switch (type) {
    case 'MAIN':
      return '主线'
    case 'SIDE':
      return '支线'
    case 'DAILY':
      return '每日'
    case 'WEEKLY':
      return '每周'
    default:
      return type
  }
}

/** 进度数字：超大的量级压成千分位，避免「1200000/2000000」这种数不清的文本。 */
function formatProgress(value: number): string {
  if (!Number.isFinite(value)) {
    return '0'
  }
  return value.toLocaleString('en-US')
}

/**
 * 一次领取的结算摘要。
 *
 * <p>奖励明细来自服务端真正发出去的东西（{@code QuestClaimResp.rewards}），
 * **不是客户端按表猜的** —— 装不下转邮件的那部分也在服务端那份明细里。
 */
export interface ClaimSummary {
  readonly questId: string
  /** 「获得：金币 ×100、卫无咎 ×1」这样的文本；无奖励时为空数组 */
  readonly rewardLines: readonly string[]
  /** 「还有 2 个奖励可领取」；没有了为 null */
  readonly remainingText: string | null
  /** 本次领到的整卡武将 id（一等多）。界面据此跳「新武将」动效 */
  readonly grantedHeroIds: readonly string[]
}

export function buildClaimSummary(resp: QuestClaimResp): ClaimSummary {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  return {
    questId: resp.questId,
    rewardLines: resp.rewards.map(rewardLine),
    remainingText: resp.claimableCount > 0 ? `还有 ${resp.claimableCount} 个奖励可领取` : null,
    grantedHeroIds: resp.rewards.filter(r => r.type === 'HERO').map(r => r.id),
  }
}

function rewardLine(reward: QuestReward): string {
  // 名字一律用服务端解析好的（协议里写明客户端不得自行翻译/拼接）
  return `${reward.name} ×${formatProgress(reward.count)}`
}

/** 三选一弹窗的数据。 */
export interface HeroChoicePrompt {
  readonly questId: string
  /** 弹出的标题（任务名原样）。界面用它回答「这是哪一条任务的奖励」 */
  readonly title: string
  /** 候选武将 id，顺序即服务端给的顺序 */
  readonly candidates: readonly HeroChoice[]
}

/**
 * 这次点「领取」应该做什么。
 *
 * <p>三态而不是布尔：界面要分别处理「直接领」「先弹选择」「什么都不用做」，
 * 用一个布尔表达会逼调用方在调用点再判一次 heroChoices 是否为空 —— 那就是第二份判定。
 */
export type ClaimIntent =
  | { readonly kind: 'claim'; readonly questId: string; readonly heroChoice: null }
  | { readonly kind: 'choose'; readonly prompt: HeroChoicePrompt }
  | { readonly kind: 'noop'; readonly reason: string }

/**
 * 判断一次「领取」点击的意图。
 *
 * <p><b>不可领取时返回 noop 而不是照样发请求</b>：服务端当然会拒（三种拒绝各有各的码），
 * 但让一个明显灰着的按钮发一次注定失败的请求，只会在看板上把「误点」记成「尝试领取」。
 */
export function claimIntentOf(row: QuestRow, title: string): ClaimIntent {
  if (!row.claimable) {
    return { kind: 'noop', reason: `任务不可领取（${row.statusText}）` }
  }
  if (row.heroChoices.length > 0) {
    return {
      kind: 'choose',
      prompt: { questId: row.questId, title, candidates: row.heroChoices },
    }
  }
  return { kind: 'claim', questId: row.questId, heroChoice: null }
}

/**
 * 在弹窗里选定一名武将后，构造 claim 请求体。
 *
 * <p><b>选中的必须在候选里</b>：界面只该从候选里给出选项，所以这里是防御性检查 ——
 * 放行一个不在候选里的 id 会被服务端拒（`heroChoice 不在候选里`），
 * 而那意味着界面把不该出现的东西画出来了，早点在本地炸掉比让玩家看到一次失败更好。
 */
export function chosenClaimReq(prompt: HeroChoicePrompt, heroId: string): {
  readonly questId: string
  readonly heroChoice: string
} {
  if (!prompt.candidates.some(c => c.heroId === heroId)) {
    throw new Error(`选中的武将 ${heroId} 不在候选里：${prompt.candidates.map(c => c.heroId).join('、')}`)
  }
  return { questId: prompt.questId, heroChoice: heroId }
}

/**
 * 候选武将的展示名。
 *
 * <p><b>直接用服务端下发的那一份</b>：协议 `HeroChoice.name` 就是 hero 表的名字，
 * 客户端不查表、不翻译、也不回落成 id —— 「客户端自己拼武将名」会让界面上的称呼
 * 与战报、客服工单里的对不上（与 QuestReward.name 同一条纪律）。
 */
export function candidateLabel(choice: HeroChoice): string {
  return choice.name
}
