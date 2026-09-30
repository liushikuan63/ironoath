/**
 * 国策视图模型的用例（B13 §4，V17-G 的纯逻辑那一半）。
 * 与 `NationSections` 同款做法：夹具是一份**协议原样**的 JSON，断言的是「翻译得对不对」。
 *
 * <b>断言能失败的另一半</b>：每一类都配了一条「服务端说灰、面板必须也灰」与
 * 「服务端说亮、面板不许自己加灰」的对照 —— 只测前者的话，客户端自己判一遍
 * 权限与时间也能通过，而那正是这个文件第一段禁止的事。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildPolicyPanel,
  policyPhaseText,
  policyProposeBlockText,
  policyVoteBlockText,
} from '../assets/scripts/game/nation/NationPolicyPanel'
import type { NationPolicyRoundView } from '../assets/scripts/net/generated/NationProtocol'


/** 取第一行并断它存在：省掉每处一遍 `!`，而「服务端给了数据、面板没画出来」正是这里要断的。 */
function first<T>(list: readonly T[]): T {
  assert.ok(list.length > 0, '这一列必须至少有一行（服务端给了数据而面板没画出来，是这一族要断的形状）')
  return list[0] as T
}

const NOW = 1_790_000_000_000
const HOUR = 3_600_000

const POLICY = {
  policyId: 'np_cavalry_t1',
  name: '骑兵时代·轻骑 T1',
  effectAttr: 'POLICY_ATTACK' as const,
  effectValueFixed: 1500,
  targetUnitName: '轻骑兵 T1',
  effectText: '轻骑兵 T1 攻击 +15%',
}

function round(over: Partial<NationPolicyRoundView> = {}): NationPolicyRoundView {
  return {
    nationId: 'n1',
    phase: 'PROPOSING',
    policySlotCount: 1,
    policies: [POLICY],
    proposals: [],
    active: [],
    canPropose: true,
    proposeBlockReason: 'NONE',
    canVote: false,
    voteBlockReason: 'NO_PROPOSAL_YET',
    myProposals: [],
    myVotes: [],
    nextVoteAt: NOW + 24 * HOUR,
    voteEndsAt: 0,
    slotOrderNote: '同轮多条提案都通过时按四级排序占槽位。',
    serverNow: NOW,
    ...over,
  } as NationPolicyRoundView
}

test('两种「等」给不同的提示：还没有提案 ≠ 窗口没开', () => {
  assert.equal(policyVoteBlockText('NO_PROPOSAL_YET'), '本轮还没有国策提案 —— 现在可以去提一条')
  assert.equal(policyVoteBlockText('NOT_VOTING'), '现在不是投票时间')
  // 合并这两个是这一页最容易犯的错：玩家会以为自己该等，而他能做的事是现在就去提一条
  assert.notEqual(policyVoteBlockText('NO_PROPOSAL_YET'), policyVoteBlockText('NOT_VOTING'))
})

test('提案理由：没提案这一位在提案门禁上是「没拦着」', () => {
  assert.equal(policyProposeBlockText('NONE'), null)
  assert.equal(policyProposeBlockText('NOT_PROPOSER'), '只有国王与官员能提出国策')
  assert.equal(policyProposeBlockText('ALREADY_PROPOSED'), '本轮已经有人提过这一条国策了')
  // NO_PROPOSAL_YET 是**投票**侧的理由，提案侧不拦人 —— 写成"此刻提不了"就反了
  assert.equal(policyProposeBlockText('NO_PROPOSAL_YET'), null)
})

test('轮次段 → 标题', () => {
  assert.equal(policyPhaseText('PROPOSING'), '提案中')
  assert.equal(policyPhaseText('VOTING'), '投票中')
  assert.equal(policyPhaseText('ACTIVE'), '国策生效中')
})

test('提案段：候选全亮，但投票那一栏必须是灰的（服务端说不能投）', () => {
  const panel = buildPolicyPanel(round(), 'king')
  assert.equal(panel.candidates.length, 1)
  assert.equal(first(panel.candidates).proposeGate.enabled, true, '国王此刻能提这一条')
  assert.equal(first(panel.candidates).voteGate.enabled, false)
  assert.equal(first(panel.candidates).voteGate.reason, '先提案，再投票')
  assert.equal(panel.countdownText.startsWith('距开票'), true)
  // 生效列表为空时给的是一句人话，而不是空白让玩家猜
  assert.equal(panel.activeText, '当前没有生效的国策')
})

test('服务端说不能提案时，候选的提案键灰掉并指名缺什么（客户端不判官职）', () => {
  const panel = buildPolicyPanel(round({ canPropose: false, proposeBlockReason: 'NOT_PROPOSER' }), 'member')
  const candidate = first(panel.candidates)
  assert.equal(candidate.proposeGate.enabled, false)
  assert.equal(candidate.proposeGate.reason, '只有国王与官员能提出国策')
})

test('本轮提过的那一条，提案键灰；没提过的仍然亮', () => {
  const withProposal = round({
    proposals: [{
      proposalId: 'p1',
      policy: POLICY,
      yes: 0,
      no: 0,
      supporters: [],
      opponents: [],
      proposedBy: 'king',
      proposedAt: NOW,
    }],
    myProposals: ['p1'],
  })
  const panel = buildPolicyPanel(withProposal, 'king')
  assert.equal(first(panel.candidates).proposeGate.enabled, false)
  assert.equal(first(panel.candidates).proposeGate.reason, '本轮已经提过这一条国策了')
})

test('投票段：票数、名单、我这一票都是从服务端那份照抄，客户端不重算', () => {
  const voting = round({
    phase: 'VOTING',
    canVote: true,
    voteBlockReason: 'NONE',
    voteEndsAt: NOW + 24 * HOUR,
    nextVoteAt: 0,
    proposals: [{
      proposalId: 'p1',
      policy: POLICY,
      yes: 2,
      no: 1,
      supporters: [{ playerId: 'a', name: '甲' }, { playerId: 'b', name: '乙' }],
      opponents: [{ playerId: 'c', name: '丙' }],
      proposedBy: 'king',
      proposedAt: NOW,
    }],
    myVotes: [{ proposalId: 'p1', support: true }],
  })
  const panel = buildPolicyPanel(voting, 'b')
  const row = first(panel.proposals)
  assert.equal(row.tallyText, '赞成 2 · 反对 1')
  assert.equal(row.supporters, '甲、乙')
  assert.equal(row.opponents, '丙')
  assert.equal(row.mySupport, true, '甲那一票能从 myVotes 里认出来')
  assert.equal(row.voteGate.enabled, false, '投过了就不许再投（这一格最贵的正是它）')
  assert.equal(row.voteGate.reason, '这一票你已经投过了')
  assert.equal(panel.countdownText.startsWith('距本轮投票结束'), true)
})

test('没投过的人在投票段能投（服务端说能，客户端不许自己加灰）', () => {
  const voting = round({
    phase: 'VOTING',
    canVote: true,
    voteBlockReason: 'NONE',
    voteEndsAt: NOW + HOUR,
    proposals: [{
      proposalId: 'p1',
      policy: POLICY,
      yes: 0, no: 0, supporters: [], opponents: [], proposedBy: 'king', proposedAt: NOW,
    }],
  })
  const panel = buildPolicyPanel(voting, 'nobody')
  const row = first(panel.proposals)
  assert.ok(row)
  assert.equal(row.voteGate.enabled, true)
  assert.equal(row.mySupport, null)
})

test('公示名单为空时说「还没有人投票」，不是空白', () => {
  const row = buildPolicyPanel(round({
    proposals: [{
      proposalId: 'p1', policy: POLICY, yes: 0, no: 0,
      supporters: [], opponents: [], proposedBy: 'king', proposedAt: NOW,
    }],
  }), 'king').proposals[0]
  assert.ok(row, '提案行必须存在')
  assert.equal(row.supporters, '还没有人投票')
  assert.equal(row.opponents, '还没有人投票')
})

test('生效中的国策给出一句人话，空的时候不装作有', () => {
  const active = buildPolicyPanel(round({ phase: 'ACTIVE', active: [POLICY] }), 'king')
  assert.equal(active.activeText, '当前生效：骑兵时代·轻骑 T1')
})

test('倒计时用服务端两个时刻相减，不用本机钟（负数说「刚刚」而不是负时长）', () => {
  // nextVoteAt 已经过去：面板要说刚刚，而不是「-5 分钟」
  const past = buildPolicyPanel(round({ nextVoteAt: NOW - 5 * 60_000 }), 'king')
  assert.equal(past.countdownText.includes('刚刚'), true)
})

test('页眉带槽位数，玩家看得见「这一轮能占几个」', () => {
  assert.equal(buildPolicyPanel(round(), 'king').header, '提案中 · 生效槽位 1')
  assert.equal(buildPolicyPanel(round({ policySlotCount: 3 }), 'king').header, '提案中 · 生效槽位 3')
})
