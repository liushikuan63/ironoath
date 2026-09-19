/**
 * 职责：创建小队/联盟那一屏的判定用例（B26 S2）。
 * 依赖：node（`node --test`）。
 *
 * <p>重点盯三件容易写错的事：政策没读到时不许猜、服务端的原因**原样**上屏（不在客户端重写一遍）、
 * 以及客户端**不发明**服务端没有的规则（标签长度服务端不判，这里就不判）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { buildCreateForm, costText, createEntry } from '../assets/scripts/game/social/SocialCreate'
import type { SocialCreatePolicy } from '../assets/scripts/net/generated/SocialProtocol'

const policy = (over: Partial<SocialCreatePolicy> = {}): SocialCreatePolicy => ({
  canCreate: true, costGold: 500, costResource: 'GOLD', reason: null, ...over,
})

test('政策还没读到：行灰着写「读取中」，表单不给确认，而不是拿默认值猜一个能点', () => {
  const entry = createEntry('alliance', null, 200)
  assert.equal(entry.enabled, false)
  assert.equal(entry.detailText, '创建条件读取中')
  const form = buildCreateForm('alliance', null, 200, '铁誓', 'TS')
  assert.equal(form.canSubmit, false)
  assert.ok(form.hint.includes('读取中'), form.hint)
})

test('服务端给的那句原因原样上屏（客户端不重写门槛，也不把倒计时自己算一遍）', () => {
  const reason = '需要主城 10 级，当前 4 级'
  const entry = createEntry('alliance', policy({ canCreate: false, reason }), 900)
  assert.equal(entry.detailText, reason)
  assert.equal(entry.enabled, false)
  assert.equal(buildCreateForm('alliance', policy({ canCreate: false, reason }), 900, '铁', 'T').hint,
    reason)
})

test('钱不够：行仍然可以点开（打开表单不会被服务端拒），但表单的「确认」灰着并写清还差多少', () => {
  const entry = createEntry('alliance', policy(), 200)
  assert.equal(entry.enabled, true)
  assert.equal(entry.detailText, '消耗 500 金币 · 还差 300')
  const form = buildCreateForm('alliance', policy(), 200, '铁誓', 'TS')
  assert.equal(form.canSubmit, false)
  assert.equal(form.hint, '还差 300 金币')
})

test('余额没读到不等于零：不能因此把确认按死（那是把"我没查到"说成"你没钱"）', () => {
  const form = buildCreateForm('alliance', policy(), null, '铁誓', 'TS')
  assert.equal(form.canSubmit, true)
  assert.equal(form.costText, '消耗 500 金币')
})

test('小队：不消耗资源、没有标签输入框，标签留空也不挡确认', () => {
  const entry = createEntry('squad', policy({ costGold: 0 }), null)
  assert.equal(entry.detailText, '不消耗资源')
  const form = buildCreateForm('squad', policy({ costGold: 0 }), null, '五个人的队', '')
  assert.equal(form.tagLabel, null)
  assert.equal(form.canSubmit, true)
})

test('名字空着不给发（服务端那句「不得为空」是真会拒的），纯空格也算空 —— 服务端会 trim', () => {
  const empty = buildCreateForm('alliance', policy(), 900, '   ', 'TS')
  assert.equal(empty.canSubmit, false)
  assert.equal(empty.hint, '先给联盟起个名字')
  assert.equal(buildCreateForm('alliance', policy(), 900, '铁誓', '  ').hint, '还要填一个标签')
})

test('客户端不发明服务端没有的规则：服务端只判非空，四十个字照样给确认', () => {
  const long = '联'.repeat(40)
  const form = buildCreateForm('alliance', policy(), 900, long, long)
  assert.equal(form.canSubmit, true)
})

test('资源中文名走 `ui/ResourceNames` 那唯一一份：换个资源类型，字跟着换', () => {
  assert.equal(costText(policy({ costResource: 'WOOD' }), 900), '消耗 500 木材 · 我有 900')
  // 表里没有的取值退回原文（不显示空串，也不崩）
  assert.equal(costText(policy({ costResource: 'NOT_A_RESOURCE' }), null), '消耗 500 NOT_A_RESOURCE')
})
