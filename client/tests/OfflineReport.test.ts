/**
 * 职责：「自上次登录以来」汇总的纯逻辑用例（B25-S3，验收 6 / 7）。
 * 依赖：node:test + game/offline/OfflineReport（不碰 cc）。
 *
 * <p><b>这两组用例盯的正是验收原文里最容易做假的两句</b>：
 * ① 「各项之和 = 对应面板里那笔账」—— 事件类条目逐条可点进去看到那一笔（条数、胜负、跳转目标都对得上），
 *    而**资源那一行是估算**（服务端没有逐窗口流水账，而"当前库存 − 上次库存"会把自动续训的花费算成产出，
 *    那是红线禁止的算法）⇒ 这里断言的是"它按产率算、且文案里写着约"，不是"它等于某个面板数字"。
 * ② 「未达阈值/已展示同一批时不弹」—— 四条闸门逐条各来一个用例，且每条都能失败（改阈值就会红）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildOfflineItems, fingerprintOf, offlineReportGate,
} from '../assets/scripts/game/offline/OfflineReport'
import type { OfflineItem, OfflineSources } from '../assets/scripts/game/offline/OfflineReport'

const HOUR = 3_600_000
/** 夹具的"现在"：任意取一个固定时刻，让所有断言都是确定的 */
const NOW_MS = 1_700_000_000_000

function sources(overrides: Partial<OfflineSources> = {}): OfflineSources {
  return {
    offlineReport: { previousLoginAt: NOW_MS - 3 * HOUR, minIdleMinutes: 10, minItems: 1 },
    serverNow: NOW_MS,
    resources: {
      WOOD: { current: 5000, cap: 20000, protectedAmount: 0, perHour: 200, lastSettle: NOW_MS },
      IRON: { current: 2000, cap: 10000, protectedAmount: 0, perHour: 100, lastSettle: NOW_MS },
      STAMINA: { current: 50, cap: 100, protectedAmount: 0, perHour: 10, lastSettle: NOW_MS },
    },
    buildings: [],
    reports: [],
    events: [],
    ...overrides,
  }
}

test('验收 6：事件类条目逐条对得上面板里那一笔（条数、胜负、跳转目标）', () => {
  const items = buildOfflineItems(sources({
    buildings: [
      { id: 'b1', configId: 'lumber_camp', name: '伐木场', level: 8, gridX: 1, gridY: 1, status: 'UPGRADING',
        finishAt: NOW_MS - 60_000, remainingSeconds: 0, progress: 10000,
        startedAt: NOW_MS - HOUR, totalSeconds: 3600, helpCount: 0 },
      { id: 'b2', configId: 'barracks', name: '兵营', level: 3, gridX: 2, gridY: 2, status: 'IDLE',
        finishAt: null, remainingSeconds: null, progress: 0,
        startedAt: 0, totalSeconds: 0, helpCount: 0 },
    ],
    reports: [
      // 窗口内的两封
      { reportId: 'r1', battleType: 'PVE', opponentId: null, opponentName: '野蛮人营地',
        winner: 'ATTACKER' as never, won: true, totalRounds: 6, attackerLoss: 10, defenderLoss: 90,
        createdAt: NOW_MS - HOUR, expiresAt: NOW_MS + 10 * HOUR },
      { reportId: 'r2', battleType: 'PVP_SOLO', opponentId: 'P9', opponentName: '邻居',
        winner: 'DEFENDER' as never, won: false, totalRounds: 8, attackerLoss: 40, defenderLoss: 12,
        createdAt: NOW_MS - 2 * HOUR, expiresAt: NOW_MS + 10 * HOUR },
      // 窗口之前的那一封：不该出现（否则"自上次登录以来"就是假的）
      { reportId: 'r0', battleType: 'PVE', opponentId: null, opponentName: '旧仗',
        winner: 'ATTACKER' as never, won: true, totalRounds: 3, attackerLoss: 1, defenderLoss: 5,
        createdAt: NOW_MS - 5 * HOUR, expiresAt: NOW_MS + 10 * HOUR },
    ],
    events: [
      { eventId: 'e1', type: 'MEMBER_ATTACKED' as never, title: '盟友 张三 正在被攻击', body: null,
        coord: null, relatedId: null, occurredAt: NOW_MS - HOUR, expired: false },
      { eventId: 'e2', type: 'MEMBER_ATTACKED' as never, title: '已经过期的旧事', body: null,
        coord: null, relatedId: null, occurredAt: NOW_MS - 2 * HOUR, expired: true },
    ],
  }))

  const byKey = new Map(items.map((i) => [i.key, i]))
  const buildings = items.find((i) => i.key === 'buildings')
  assert.equal(buildings?.text, '1 座建筑已升级完成', '只数"到点待收割"的那几座（IDLE 的不是）')
  assert.equal(buildings?.jump, 'city')
  const battles = items.find((i) => i.key.startsWith('battles:'))
  assert.equal(battles?.text, '2 场战斗', '只数窗口内的战报，窗口之前那封不算')
  assert.equal(battles?.detail, '胜 1 · 败 1')
  assert.equal(battles?.jump, 'reports')
  const social = items.find((i) => i.key.startsWith('social:'))
  assert.equal(social?.text, '1 条社交动态', '过期的旧事不列（列一条点不动的比不列更糟）')
  assert.equal(social?.detail, '盟友 张三 正在被攻击', '明细给第一条标题，玩家一眼知道是什么')
  assert.equal(social?.jump, 'social')
  assert.equal(byKey.size, items.length, '每条的 key 唯一')
})

test('验收 6 的边界：资源那一行是"约"的估算，不与任何面板数字做等式', () => {
  const items = buildOfflineItems(sources())
  const res = items.find((i) => i.key === 'resources')
  assert.equal(res?.text, '资源产出（约 3 小时）', '文案里必须写"约"—— 它是 perHour × 窗口，不是流水账')
  assert.equal(res?.detail, '木材 +600、铁矿 +300',
    '按产率算：3 小时 × 200/100；体力不列（它不按产率走）')
  assert.equal(res?.jump, 'city')
})

test('验收 7：新号没有「上一次」⇒ 一条不列、也不弹', () => {
  const off = sources({ offlineReport: { previousLoginAt: null, minIdleMinutes: 10, minItems: 1 } })
  assert.deepEqual(buildOfflineItems(off), [], '没有起点就没有"自上次登录以来"')
  const gate = offlineReportGate(off.offlineReport, [], NOW_MS, null)
  assert.equal(gate.show, false)
  assert.match(gate.reason ?? '', /新号/)
})

test('验收 7：离得太近或没有可说的都不弹（阈值全部来自服务端下发的那两个数）', () => {
  const items = buildOfflineItems(sources())
  assert.ok(items.length > 0, '夹具前提：这一批是有东西可说的')

  const tooSoon = { previousLoginAt: NOW_MS - 5 * 60_000, minIdleMinutes: 10, minItems: 1 }
  const soon = offlineReportGate(tooSoon, items, NOW_MS, null)
  assert.equal(soon.show, false, '距上次登录 5 分钟、阈值 10 分钟 ⇒ 不打扰')
  assert.match(soon.reason ?? '', /5 分钟/)

  const enough = offlineReportGate({ previousLoginAt: NOW_MS - 30 * 60_000, minIdleMinutes: 10, minItems: 1 },
    items, NOW_MS, null)
  assert.equal(enough.show, true, '过了时长门槛且有内容 ⇒ 该弹')

  const empty = offlineReportGate({ previousLoginAt: NOW_MS - 3 * HOUR, minIdleMinutes: 10, minItems: 1 },
    [], NOW_MS, null)
  assert.equal(empty.show, false, '一条可说的都没有 ⇒ 不弹一个空面板')
  assert.match(empty.reason ?? '', /至少/)

  const strict = offlineReportGate({ previousLoginAt: NOW_MS - 3 * HOUR, minIdleMinutes: 10, minItems: 3 },
    items, NOW_MS, null)
  assert.equal(strict.show, false, '阈值调高（表参数改 3）⇒ 同一批明细就不弹了：阈值真的是表里那个数')
})

test('验收 7：同一批明细不重复展示；但新出现的那条要能通过', () => {
  const first = buildOfflineItems(sources())
  const gate = offlineReportGate({ previousLoginAt: NOW_MS - 3 * HOUR, minIdleMinutes: 10, minItems: 1 },
    first, NOW_MS, null)
  assert.equal(gate.show, true)
  assert.notEqual(gate.fingerprint, null)

  const again = offlineReportGate({ previousLoginAt: NOW_MS - 3 * HOUR, minIdleMinutes: 10, minItems: 1 },
    first, NOW_MS, gate.fingerprint)
  assert.equal(again.show, false, '同一批已经给他看过 ⇒ 不再弹')
  assert.match(again.reason ?? '', /已经给他看过/)

  // 打完一场仗回来：明细变了（多一场战斗），指纹也就变了 ⇒ 该说
  const withBattle = buildOfflineItems(sources({
    reports: [{ reportId: 'r9', battleType: 'PVE', opponentId: null, opponentName: '新打的一仗',
      winner: 'ATTACKER' as never, won: true, totalRounds: 5, attackerLoss: 3, defenderLoss: 30,
      createdAt: NOW_MS - 60_000, expiresAt: NOW_MS + 10 * HOUR }],
  }))
  assert.notEqual(fingerprintOf(withBattle), fingerprintOf(first))
  const afterBattle = offlineReportGate({ previousLoginAt: NOW_MS - 3 * HOUR, minIdleMinutes: 10, minItems: 1 },
    withBattle, NOW_MS, gate.fingerprint)
  assert.equal(afterBattle.show, true, '新出现的那一条要能说 —— 否则玩家会漏掉真正的变化')
})

test('验收 7：没有伪称"离线时长"——标题口径是"自上次登录以来"', () => {
  const items: readonly OfflineItem[] = buildOfflineItems(sources())
  const res = items.find((i) => i.key === 'resources')
  assert.doesNotMatch(res?.text ?? '', /离线/, '裁决①(a)：这个边界是"上次登录"，不能称离线时长（含上次在线期间）')
  const battle = items.find((i) => i.key.startsWith('battles:'))
  assert.doesNotMatch(battle?.text ?? '', /离线/)
})
