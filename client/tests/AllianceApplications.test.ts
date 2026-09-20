/**
 * 职责：「入盟申请」那一屏的判定用例（B26 S8）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯三条：昵称与城等只用服务端下发的（客户端没有玩家表）、有界列表要说清总量、
 * 以及"空表"与"读不到"必须是两句话。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { buildApplications, EMPTY_APPLICATIONS } from '../assets/scripts/game/social/AllianceApplications'
import type { AllianceApplicationListResp } from '../assets/scripts/net/generated/SocialProtocol'

const SERVER_NOW = 1_788_000_000_000

const resp = (over: Partial<AllianceApplicationListResp> = {}): AllianceApplicationListResp => ({
  applicants: [
    { playerId: 'p_a', nickname: '阿铁', mainCityLevel: 7 },
    { playerId: 'p_b', nickname: '老周', mainCityLevel: 3 },
  ],
  total: 2, limit: 50, serverNow: SERVER_NOW, ...over,
})

test('读不到写「读取中」，不写成"没有申请"——盟主会以为自己没收到过', () => {
  const view = buildApplications(null)
  assert.deepEqual(view.rows, [])
  assert.equal(view.notice, '申请名单读取中')
})

test('一行一个人：昵称与主城等级都用服务端那一份', () => {
  const view = buildApplications(resp())
  assert.deepEqual(view.rows.map(r => [r.id, r.titleText, r.detailText]), [
    ['p_a', '阿铁', '主城 7 级'],
    ['p_b', '老周', '主城 3 级'],
  ])
  assert.equal(view.notice, '', '两条都画下了就不需要那句')
})

test('有界列表说清总量：只显示前 50 条时不能假装这就是全部', () => {
  assert.equal(buildApplications(resp({ total: 214 })).notice,
    '共 214 条待处理，这里只显示前 2 条')
})

test('空表是一句人话，不是空白', () => {
  const view = buildApplications(resp({ applicants: [], total: 0 }))
  assert.deepEqual(view.rows, [])
  assert.equal(view.notice, '暂时没有待处理的申请')
})

test('EMPTY_APPLICATIONS 既不画行也不写说明（用于"没权限、这一段根本不该出现"）', () => {
  assert.deepEqual(EMPTY_APPLICATIONS.rows, [])
  assert.equal(EMPTY_APPLICATIONS.notice, '')
})
