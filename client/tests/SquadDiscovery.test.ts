/**
 * 职责：「可加入小队」那一屏的判定用例（B26 S7）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯三条：满不满只看服务端那个布尔（上限挂在队长主城等级上，客户端没有那份读数）、
 * 有界列表要说清总量、以及读不到 ≠ 没有队可加。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { buildSquadDiscovery, canJoin } from '../assets/scripts/game/social/SquadDiscovery'
import type { SquadListResp } from '../assets/scripts/net/generated/SocialProtocol'

const SERVER_NOW = 1_788_000_000_000

const resp = (over: Partial<SquadListResp> = {}): SquadListResp => ({
  squads: [
    { id: 'sq_a', name: '铁血队', level: 2, memberCount: 3, memberCap: 5, full: false },
    { id: 'sq_b', name: '雪岭队', level: 1, memberCount: 5, memberCap: 5, full: true },
  ],
  total: 12, limit: 20, serverNow: SERVER_NOW, ...over,
})

test('列表没读到时说「读取中」，而不是画一张空表假装"没人建队"', () => {
  const view = buildSquadDiscovery(null)
  assert.deepEqual(view.rows, [])
  assert.equal(view.notice, '可加入小队读取中')
})

test('两态各有各的按钮文字与原因：能加、已满', () => {
  const view = buildSquadDiscovery(resp())
  assert.deepEqual(view.rows.map(r => [r.titleText, r.memberText, r.actionText, r.enabled]), [
    ['铁血队', 'Lv2 · 3/5 人', '加入', true],
    ['雪岭队', 'Lv1 · 5/5 人', '已满', false],
  ])
  assert.equal(view.rows[1]?.reason, '位置满了，等队长把主城提上去或有人离开')
  assert.equal(view.rows[0]?.reason, '', '能加的那一行不该占一句警告')
})

test('人数看着没满但服务端说满 —— 以服务端为准：上限那一档只有它算得出', () => {
  const view = buildSquadDiscovery(resp({
    squads: [{ id: 'sq_x', name: '铁血队', level: 2, memberCount: 3, memberCap: 5, full: true }],
  }))
  assert.equal(view.rows[0]?.actionText, '已满')
  assert.equal(canJoin(view, 'sq_x'), false)
})

test('有界列表要说清总量：只显示前 2 支时不能假装这就是全部', () => {
  assert.equal(buildSquadDiscovery(resp()).notice, '共 12 支小队，这里只显示前 2 支')
  assert.equal(buildSquadDiscovery(resp({ total: 2 })).notice, '', '全画下了就不需要那句')
  assert.equal(buildSquadDiscovery(resp({ squads: [], total: 0 })).notice, '还没有人建立小队')
})

test('canJoin 只认列表里那一行的状态：不在列表上的 id 一律不发', () => {
  const view = buildSquadDiscovery(resp())
  assert.equal(canJoin(view, 'sq_a'), true)
  assert.equal(canJoin(view, 'sq_b'), false)
  assert.equal(canJoin(view, 'sq_not_listed'), false)
})
