/**
 * 职责：「任命职位」弹层选项的用例（B26 S11）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯两条：盟主不在选项里（那是转让），以及这里不替服务端判权限。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { ASSIGNABLE_ROLES, roleChoices } from '../assets/scripts/game/social/AllianceRoles'

test('选项只有副盟主 / 长老 / 成员：把盟主给出去是转让那件事，不混在一颗按钮里', () => {
  assert.deepEqual(ASSIGNABLE_ROLES, ['OFFICER', 'ELDER', 'MEMBER'])
  assert.deepEqual(roleChoices(null).map(c => c.id), ['OFFICER', 'ELDER', 'MEMBER'])
})

test('中文职位名沿用那唯一一份映射（弹层与行标签不许各写一套）', () => {
  assert.deepEqual(roleChoices(null).map(c => c.label), ['副盟主', '长老', '成员'])
})

test('现任那一项标出来，其余不标', () => {
  const rows = roleChoices('ELDER')
  assert.deepEqual(rows.map(c => c.current), [false, true, false])
})

test('收回职位那一项要写清后果：它是这条链路上唯一会让人变弱的一个', () => {
  const member = roleChoices('OFFICER').find(c => c.id === 'MEMBER')
  assert.ok(member !== undefined)
  assert.equal(member?.detail, '收回职位，降为普通成员')
})

test('未知/空职位不炸：只是没有一项被标成现任', () => {
  assert.deepEqual(roleChoices(null).every(c => !c.current), true)
  assert.deepEqual(roleChoices('WHATEVER').every(c => !c.current), true)
})
