/**
 * 职责：国策那一页的视图模型（B13 §4，2026-09-30）—— 提案 / 投票 / 公示 / 生效中。
 * 依赖：生成的协议类型 + `NationPanel` 的既有助手（`permissionGateOf` / `elapsedText`）。**无 Cocos**。
 *
 * <p><b>这一页最容易犯的错是「客户端自己算」</b>，而三处结论服务端都已经算好了：
 * ① 能不能提案 / 能不能投票以及**是哪一种不行**（`proposeBlockReason` / `voteBlockReason`）；
 * ② 开窗时刻与倒计时（`nextVoteAt` / `voteEndsAt` / `serverNow` 同源，铁律 5 禁止两侧各算一遍）；
 * ③ 槽位怎么分（`slotOrderNote`，一句可直接上屏的说明）。
 * 所以本文件**只做翻译**，一个判定都不自己下 —— 判据是每条文案都指向服务端给的那一位。
 *
 * <p><b>「两种「等」不许合并成一句话</b>：`NO_PROPOSAL_YET`（本轮还没有提案，
 * 玩家的下一步是自己提一条）与 `NOT_VOTING`（窗口没开，下一步是等）必须给不同的提示 ——
 * 合并之后玩家会以为自己该等，而实际上他能做的事是现在就去提一条。
 */
import type {
  NationPolicyBlockReason,
  NationPolicyProposalView,
  NationPolicyRoundView,
} from '../../net/generated/NationProtocol'
import { remainingText } from '../ui/ElapsedText'
import type { NationGate } from './NationPanel'

/** 提案理由 → 玩家语言。`null` = 没拦着。 */
export function policyProposeBlockText(reason: string): string | null {
  switch (reason) {
    case 'NONE':
      return null
    case 'NOT_PROPOSER':
      return '只有国王与官员能提出国策'
    case 'NOT_VOTING':
      return '投票已经开始了，这一轮不能再提新提案'
    case 'ALREADY_PROPOSED':
      return '本轮已经有人提过这一条国策了'
    case 'NO_PROPOSAL_YET':
      return null
    case 'BOT_NOT_ALLOWED':
      return '人机账号不参与国策'
    default:
      return '此刻提不了国策'
  }
}

/** 投票理由 → 玩家语言。`null` = 没拦着。 */
export function policyVoteBlockText(reason: string): string | null {
  switch (reason) {
    case 'NONE':
      return null
    case 'NO_PROPOSAL_YET':
      return '本轮还没有国策提案 —— 现在可以去提一条'
    case 'NOT_VOTING':
      return '现在不是投票时间'
    case 'ALREADY_VOTED':
      return '这一票你已经投过了'
    case 'BOT_NOT_ALLOWED':
      return '人机账号不参与国策投票'
    case 'NOT_PROPOSER':
      return '此刻投不了票'
    default:
      return '此刻投不了票'
  }
}

/** 轮次段 → 一句话标题。 */
export function policyPhaseText(phase: string): string {
  switch (phase) {
    case 'PROPOSING':
      return '提案中'
    case 'VOTING':
      return '投票中'
    case 'ACTIVE':
      return '国策生效中'
    default:
      return '国策'
  }
}

export interface PolicyRow {
  /** 提案 id（投票时回传；提案为空时为 null）。 */
  readonly proposalId: string | null
  /** 国策 id。 */
  readonly policyId: string
  /** 国策名（服务端下发，客户端不硬编码）。 */
  readonly name: string
  /** 可上屏的效果说明（服务端拼好，客户端不拿 attr × value × unit 自己拼）。 */
  readonly effectText: string
  /** 票数那一行：「赞成 3 · 反对 1」。 */
  readonly tallyText: string
  /** 公示名单：赞成者 / 反对者，服务端已给中文名。 */
  readonly supporters: string
  readonly opponents: string
  /** 我这一票（null = 还没投）。 */
  readonly mySupport: boolean | null
  /** 三态门禁：亮 / 灰 + 理由。灰的时候点下去零请求。 */
  readonly voteGate: NationGate
  /** 我能不能再提一次同一条（提案为空时才有意义）。 */
  readonly proposeGate: NationGate
}

export interface PolicyPanel {
  readonly phase: string
  readonly phaseText: string
  /** 可直接上屏的槽位说明（服务端给的 `slotOrderNote`）。 */
  readonly slotNote: string
  /** 距离开窗或结束还有多久（服务端时刻算好的，客户端只做减法）。 */
  readonly countdownText: string
  /** 正在生效的国策（空数组时这一行是「当前没有生效的国策」）。 */
  readonly activeText: string
  /** 提案下拉的候选（表里的全部国策）。 */
  readonly candidates: readonly PolicyRow[]
  /** 本轮的提案。 */
  readonly proposals: readonly PolicyRow[]
  /** 页签顶部的说明。 */
  readonly header: string
}

/**
 * 协议视图 → 面板模型。
 *
 * @param viewerId 调用者自己（用来从 `myVotes` 里认出「我投了什么」）
 */
export function buildPolicyPanel(round: NationPolicyRoundView, viewerId: string): PolicyPanel {
  const myVotes = new Map<string, boolean>()
  for (const vote of round.myVotes ?? []) {
    myVotes.set(vote.proposalId, vote.support)
  }
  // 「本轮我提过的那些国策」从 proposals 里按提案人认，**不用 myProposals** ——
  // 那一列是提案 id，而候选要按 policyId 判；绕一层映射就多一个能对不上的地方。
  const myProposedPolicyIds = new Set<string>()
  for (const proposal of round.proposals ?? []) {
    if (proposal.proposedBy === viewerId) {
      myProposedPolicyIds.add(proposal.policy.policyId)
    }
  }

  const candidates: PolicyRow[] = (round.policies ?? []).map(policy => ({
    proposalId: null,
    policyId: policy.policyId,
    name: policy.name,
    effectText: policy.effectText,
    tallyText: '',
    supporters: '',
    opponents: '',
    mySupport: null,
    // 能不能提这一条 = 能不能提案 ∧ 本轮还没提过这一条
    proposeGate: mergeGate(
      gateOf(round.canPropose, round.proposeBlockReason, policyProposeBlockText),
      myProposedPolicyIds.has(policy.policyId)
        ? { enabled: false, reason: '本轮已经提过这一条国策了' }
        : { enabled: true, reason: '' },
    ),
    voteGate: { enabled: false, reason: '先提案，再投票' },
  }))

  const proposals: PolicyRow[] = (round.proposals ?? []).map(proposal => rowOf(round, proposal, myVotes))

  const countdownTarget = round.phase === 'VOTING' ? round.voteEndsAt : round.nextVoteAt
  // **两个时刻相减**（不是拿本机钟）：`remainingText` 刻意不引本机时钟（铁律 5），
  // 这里传的是 `countdownTarget - serverNow`，两端同源。
  // 用 `remainingText` 而不是 `elapsedText`：后者的每句话都带「前」，
  // 倒计时用它会读成「距开票 23 小时前」—— 方向反了，而且是通顺的错话。
  const countdownText = countdownTarget > 0
    ? `${round.phase === 'VOTING' ? '本轮投票还剩' : '距开票还有'} ${remainingText(countdownTarget - round.serverNow)}`
    : '本轮还没有提案 —— 国王或官员可以先提一条'

  return {
    phase: round.phase,
    phaseText: policyPhaseText(round.phase),
    slotNote: round.slotOrderNote,
    countdownText,
    activeText: describeActive(round),
    candidates,
    proposals,
    header: `${policyPhaseText(round.phase)} · 生效槽位 ${round.policySlotCount}`,
  }
}

function rowOf(
  round: NationPolicyRoundView,
  proposal: NationPolicyProposalView,
  myVotes: Map<string, boolean>,
): PolicyRow {
  const mine = myVotes.has(proposal.proposalId) ? myVotes.get(proposal.proposalId) ?? null : null
  // 「我能不能投这一条」= 轮次级可以投 ∧ 这一条我还没投过。
  // 第二项必须在这里判而不是让界面自己比 myVotes —— 那是第二个家，而这一格最贵的正是它。
  const alreadyVoted = mine !== null
  const base = gateOf(round.canVote, round.voteBlockReason, policyVoteBlockText)
  const voteGate: NationGate = alreadyVoted && base.enabled
    ? { enabled: false, reason: '这一票你已经投过了' }
    : base
  return {
    proposalId: proposal.proposalId,
    policyId: proposal.policy.policyId,
    name: proposal.policy.name,
    effectText: proposal.policy.effectText,
    tallyText: `赞成 ${proposal.yes} · 反对 ${proposal.no}`,
    supporters: names(proposal.supporters),
    opponents: names(proposal.opponents),
    mySupport: mine,
    voteGate,
    proposeGate: { enabled: false, reason: '这一条已经提过了' },
  }
}

function describeActive(round: NationPolicyRoundView): string {
  const active = round.active ?? []
  if (active.length === 0) {
    return '当前没有生效的国策'
  }
  // 「还剩多久」由**每一行自己的 activeUntil** 回答（服务端下发，客户端两个同源时刻相减）：
  // 拿整轮一个时刻去减所有行，在两条国策到期时刻不同的时候就会有一条显示错。
  const parts = active.map(one =>
    `${one.policy.name}（还剩 ${remainingText(one.activeUntil - round.serverNow)}）`)
  return `当前生效：${parts.join('、')}`
}

function names(voters: readonly { name: string }[] | undefined): string {
  if (voters === undefined || voters.length === 0) {
    return '还没有人投票'
  }
  return voters.map(v => v.name).join('、')
}

function gateOf(can: boolean, reason: NationPolicyBlockReason, text: (r: string) => string | null): NationGate {
  if (can) {
    return { enabled: true, reason: '' }
  }
  return { enabled: false, reason: text(reason) ?? '此刻不行' }
}

/** 两个门禁的合成：任一关着就关着，而<b>理由取先关的那一个</b>（先判的在前）。 */
function mergeGate(first: NationGate, second: NationGate): NationGate {
  if (!first.enabled) {
    return first
  }
  return second.enabled ? first : second
}
