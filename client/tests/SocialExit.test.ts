/**
 * 职责：退出与解散那四行的判定用例（B26 S3）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯的是两条容易写反的不对称：退出是每个成员的权利（服务端没有 LEAVE 这一位），
 * 解散才看权限；以及"第一下不发请求"。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { exitEntry, exitLabel, transferEntry } from '../assets/scripts/game/social/SocialExit'
import type { Gate } from '../assets/scripts/game/social/PermissionGates'

const ALLOWED: Gate = { allowed: true, reason: null }
const DENIED: Gate = { allowed: false, reason: '你当前的职位不能做这件事' }

test('退出不要权限（服务端没有 LEAVE 这一位），解散要 —— 写成一样就把能退队的人关在队里', () => {
  const leave = exitEntry('squad', 'leave', true, null, false)
  assert.equal(leave.enabled, true)
  assert.equal(leave.detailText, '')
  assert.equal(exitEntry('squad', 'disband', true, DENIED, false).enabled, false)
})

test('解散被拒时把服务端那句原话写在行上，不出现权限码', () => {
  const entry = exitEntry('alliance', 'disband', true, DENIED, false)
  assert.equal(entry.detailText, '你当前的职位不能做这件事')
  assert.equal(entry.detailText.includes('DISBAND_ALLIANCE'), false)
})

test('第一下只把键改成「确认…」，字变了但请求要等第二下（这条判据由 AppRoot 用例配套）', () => {
  assert.equal(exitLabel('alliance', 'leave', false), '退出联盟')
  assert.equal(exitLabel('alliance', 'leave', true), '确认退出联盟')
  assert.equal(exitLabel('squad', 'disband', true), '确认解散小队')
  const armed = exitEntry('alliance', 'disband', true, ALLOWED, true)
  assert.equal(armed.enabled, true)
  assert.equal(armed.detailText, '再点一次组织就没了，成员各自散去',
    '变字之外还要说清下一次会发生什么')
})

test('没加入时灰着并给一句话，而不是把行藏掉（藏掉玩家会以为没有这个功能）', () => {
  const entry = exitEntry('squad', 'leave', false, null, false)
  assert.equal(entry.enabled, false)
  assert.equal(entry.detailText, '还没加入，不用退出')
})

test('armed 只点亮被按过的那一行（按了退队不会让解散键也变成"确认"）', () => {
  const leave = exitEntry('squad', 'leave', true, null, true)
  const disband = exitEntry('squad', 'disband', true, ALLOWED, false)
  assert.equal(leave.actionText, '确认退出小队')
  assert.equal(disband.actionText, '解散小队')
})

test('转让看 TRANSFER_LEADER 那一位：没有它就灰着并写原因（服务端判位不判职位名）', () => {
  const denied = transferEntry(DENIED, false)
  assert.equal(denied.enabled, false)
  assert.equal(denied.actionText, '转让')
  assert.equal(denied.detailText, '你当前的职位不能做这件事')
  const ok = transferEntry(ALLOWED, false)
  assert.equal(ok.enabled, true)
  assert.equal(ok.detailText, '', '没按下第一下就不该占着一行字')
})

test('转让同样两下才算数：第一下只改字并说清"自己降为成员"', () => {
  const armed = transferEntry(ALLOWED, true)
  assert.equal(armed.actionText, '确认转让')
  assert.ok(armed.detailText.includes('自己降为成员'), armed.detailText)
})
