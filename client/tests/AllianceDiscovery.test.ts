/**
 * 职责：「可申请联盟」那一屏的判定用例（B26 S6）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯的是三条：结论只用服务端下发的（不自己比大小）、有界列表要说清总量、
 * 以及每一种灰态都得有一句人话原因。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { buildDiscovery, canApply } from '../assets/scripts/game/social/AllianceDiscovery'
import type { AllianceListResp } from '../assets/scripts/net/generated/SocialProtocol'

const SERVER_NOW = 1_788_000_000_000

const resp = (over: Partial<AllianceListResp> = {}): AllianceListResp => ({
  alliances: [
    { id: 'al_a', name: '铁誓', tag: 'TS', level: 3, memberCount: 4, memberCap: 30,
      full: false, applied: false },
    { id: 'al_b', name: '铜雀', tag: 'QQ', level: 1, memberCount: 30, memberCap: 30,
      full: true, applied: false },
    { id: 'al_c', name: '雪岭', tag: 'XL', level: 2, memberCount: 12, memberCap: 20,
      full: false, applied: true },
  ],
  total: 57, limit: 20, serverNow: SERVER_NOW, ...over,
})

test('列表没读到时说「读取中」，而不是画一张空表假装"没有联盟可申请"', () => {
  const view = buildDiscovery(null)
  assert.deepEqual(view.rows, [])
  assert.equal(view.notice, '可申请联盟读取中')
})

test('三种状态各有各的按钮文字与原因：满、已申请、能申', () => {
  const view = buildDiscovery(resp())
  assert.deepEqual(view.rows.map(r => [r.titleText, r.memberText, r.actionText, r.enabled]), [
    ['[TS] 铁誓', 'Lv3 · 4/30 人', '申请加入', true],
    ['[QQ] 铜雀', 'Lv1 · 30/30 人', '已满', false],
    ['[XL] 雪岭', 'Lv2 · 12/20 人', '已申请', false],
  ])
  assert.equal(view.rows[1]?.reason, '位置满了，等他们扩容或有人离开')
  assert.equal(view.rows[2]?.reason, '等盟主或官员审核')
  assert.equal(view.rows[0]?.reason, '', '能申的那一行不该占一句警告')
})

test('满不满与申请过没有都只看服务端那两个布尔：客户端不拿 memberCount 自己比', () => {
  // 人数看着没满，但服务端说 full —— 以服务端为准（上限含扩容后的口径，只有它有账）
  const view = buildDiscovery(resp({
    alliances: [{ id: 'al_x', name: '铁誓', tag: 'TS', level: 3, memberCount: 4,
      memberCap: 30, full: true, applied: false }],
  }))
  assert.equal(view.rows[0]?.actionText, '已满')
  assert.equal(canApply(view, 'al_x'), false)
})

test('有界列表要说清总量：只显示前 20 个时不能假装这就是全部', () => {
  assert.equal(buildDiscovery(resp()).notice, '共 57 个联盟，这里只显示前 3 个')
  assert.equal(buildDiscovery(resp({ total: 3 })).notice, '', '全画下了就不需要那句')
  assert.equal(buildDiscovery(resp({ alliances: [], total: 0 })).notice, '还没有人建立联盟')
})

test('canApply 只认列表里那一行的状态：不在列表上的 id 一律不发', () => {
  const view = buildDiscovery(resp())
  assert.equal(canApply(view, 'al_a'), true)
  assert.equal(canApply(view, 'al_c'), false)
  assert.equal(canApply(view, 'al_not_listed'), false)
})
