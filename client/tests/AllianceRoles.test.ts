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
  // 先钉住"有项可标"：`every` 在空数组上恒真，清空常量表这条用例也不会红（#342 同族）
  const none = roleChoices(null)
  const weird = roleChoices('WHATEVER')
  assert.ok(none.length > 0, '职位菜单必须给出候选，否则下面两句什么都没说')
  assert.deepEqual(none.every(c => !c.current), true)
  assert.equal(weird.length, none.length, '认不出的职位与空职位给出同一份候选')
  assert.deepEqual(weird.every(c => !c.current), true)
})
