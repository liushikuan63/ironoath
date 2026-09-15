/**
 * 职责：邮件面板的展示组装用例（B12 §2）。
 * 依赖：node:test + game/mail/MailPanel（纯逻辑，不碰 cc）。
 *
 * <p><b>这些用例盯的是两件事</b>：① 客户端<b>不自己判</b>能不能领（铁律 2）——
 * 服务端给的两个布尔变了，界面必须跟着变；② 相对时间的写法不会把「还剩 3 天」算成「3 天前」。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildMailPanel, claimOutcomeText } from '../assets/scripts/game/mail/MailPanel'
import type { MailListResp, MailView } from '../assets/scripts/net/generated/MailProtocol'

const NOW = 1_800_000_000_000
const DAY = 86_400_000

function mail(overrides: Partial<MailView> & { mailId: string }): MailView {
  return {
    kind: 'SYSTEM', title: '标题', text: '正文', rewards: [],
    claimed: true, read: true, createdAt: NOW - DAY, expireAt: NOW + 29 * DAY,
    sourceRef: 'test', ...overrides,
  }
}

function list(mails: readonly MailView[], unreadCount = 0): MailListResp {
  return { mails: mails as MailView[], unreadCount, claimedCount: 0 }
}

test('空邮箱说「邮箱是空的」，而不是一句看起来像没连上的话', () => {
  const panel = buildMailPanel(list([], 0), NOW)

  assert.equal(panel.headerText, '邮箱是空的')
  assert.deepEqual(panel.rows, [])
  assert.equal(panel.claimAllEnabled, false, '没什么可领就不该让按钮亮着')
})

test('可领 = 服务端说没领过 且 有附件：两者缺一都不算（客户端不加第三种判据）', () => {
  const rows = buildMailPanel(list([
    mail({ mailId: 'm-claim', claimed: false, rewards: [{ type: 'RESOURCE', id: 'GOLD', count: 5, name: '金币' }] }),
    mail({ mailId: 'm-claimed', claimed: true, rewards: [{ type: 'RESOURCE', id: 'GOLD', count: 5, name: '金币' }] }),
    mail({ mailId: 'm-notice', claimed: true, rewards: [] }),
  ], 3), NOW).rows

  assert.deepEqual(rows.map(r => [r.mailId, r.claimable]),
    [['m-claim', true], ['m-claimed', false], ['m-notice', false]],
    '已经领过的与纯通知的都不能再领')
  assert.equal(rows[1]?.statusText, '已领取')
  assert.equal(rows[2]?.statusText, '纯通知')
  assert.equal(rows[0]?.statusText, '可领取')
})

test('附件名照搬服务端解析好的名字，客户端不查表也不自己拼稀有度', () => {
  const rows = buildMailPanel(list([mail({
    mailId: 'm-a',
    rewards: [
      { type: 'RESOURCE', id: 'GOLD', count: 500, name: '金币' },
      { type: 'HERO_FRAGMENT', id: 'hero_ssr_02', count: 30, name: 'SSR 武将碎片' },
    ],
  })]), NOW).rows

  assert.equal(rows[0]?.attachmentText, '金币 ×500 · SSR 武将碎片 ×30')
  assert.equal(rows[0]?.statusText, '已领取', 'claimed=true 的邮件即使带着附件也不再可领')
  assert.equal(rows[0]?.claimable, false)
})

test('未读封数用服务端给的，行顺序用服务端给的顺序（红点与列表必须同源）', () => {
  const panel = buildMailPanel(list([
    mail({ mailId: 'm-new', read: false, createdAt: NOW }),
    mail({ mailId: 'm-old', read: true, createdAt: NOW - DAY }),
  ], 1), NOW)

  assert.equal(panel.unreadCount, 1, '不自己数：数法一旦与服务端不同，徽标会和列表各说一套')
  assert.deepEqual(panel.rows.map(r => r.mailId), ['m-new', 'm-old'])
  assert.deepEqual(panel.rows.map(r => r.unread), [true, false])
})

test('剩余时间三种量级各一句话，而过期那封只可能来自时钟边界 —— 不许出现负数', () => {
  const rows = buildMailPanel(list([
    mail({ mailId: 'm-3d', expireAt: NOW + 3 * DAY + 1 }),
    mail({ mailId: 'm-3h', expireAt: NOW + 3 * 3_600_000 }),
    mail({ mailId: 'm-edge', expireAt: NOW - 1 }),
  ]), NOW).rows

  assert.deepEqual(rows.map(r => r.expiresIn), ['3 天后到期', '3 小时内到期', '今天到期'])
})

test('领取结果：领到的与没领到的都要说，而只说一件的用例会让玩家反复点同一个按钮', () => {
  assert.equal(claimOutcomeText(2, ['金币 ×500', '加速卡 ×1'], []),
    '已领取 2 封：金币 ×500、加速卡 ×1')
  const partial = claimOutcomeText(1, ['金币 ×10'],
    [{ mailId: 'm-bad', reason: '背包已满' }])
  assert.match(partial, /已领取 1 封/)
  assert.match(partial, /1 封没领到（背包已满），仍留在邮箱里/)
  assert.equal(claimOutcomeText(0, [], []), '没有可领的附件')
})
