/**
 * 职责：BlockedPickCopy 的单测 —— 那几句"这一步还点不了"的玩家文案里不许出现内部 id 与工程黑话。
 * 依赖：node:test / node:assert。
 *
 * <p><b>盯的是什么</b>：#255 建筑名、#268 资源名、#278 技能名、#281 碎片名、#288 赛季行 id 这一族
 * 第六次复发的形态，是 `挑战 "stage_03"` / `分享 "rpt_1024"` 这种把内部编号拼进提示的写法
 * （以及后半句「…选择器未接入」）。调用点由 `scripts/check-player-copy-jargon.js` 兜字面量，
 * 这一份兜的是**拼装本身**：万一有人把 `stageId` 当 `what` 传进来，这里先红。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { actionUnavailable, pickUnavailable } from '../assets/scripts/game/ui/BlockedPickCopy'

/** 工程黑话：玩家读不出意思的那类词（与门里的词表同源，但这一份只测拼装结果）。 */
const JARGON = ['未接入', '未实现', '接口', '字段', '下发', '落库', '选择器', 'configId', '协议', '契约']
/** 内部编号的形态：关卡 id、战报 id、配置行 id。 */
const ID_LIKE = [/stage[_-]/i, /report[_-]/i, /\brpt\b/i, /_t\d/, /[a-z]+_\d{2,}/]

function assertPlayerReadable(label: string, text: string): void {
  assert.ok(text.length > 0, `${label}：空字符串不是一句提示`)
  for (const word of JARGON) {
    assert.ok(!text.includes(word), `${label}：出现工程黑话「${word}」—— ${text}`)
  }
  for (const pattern of ID_LIKE) {
    assert.ok(!pattern.test(text), `${label}：出现内部 id ${pattern} —— ${text}`)
  }
}

test('「要先选 X」那几句说的是人话，且把要选的东西带在句子里', () => {
  for (const [what, text] of [
    ['出战阵容', pickUnavailable('出战阵容')],
    ['发到哪个频道', pickUnavailable('发到哪个频道')],
    ['加速的队列', pickUnavailable('加速的队列')],
  ] as const) {
    assertPlayerReadable(`pickUnavailable(${what})`, text)
    assert.ok(text.includes(what), `句子里没带「${what}」，玩家不知道要选什么：${text}`)
  }
})

test('「点不出动作」那几句同样不露 id 与黑话', () => {
  assertPlayerReadable('actionUnavailable(举报或拉黑)', actionUnavailable('举报或拉黑'))
  assertPlayerReadable('actionUnavailable(解除拉黑)', actionUnavailable('解除拉黑'))
})

test('把内部编号当"要选的东西"传进来会被这一条拦下', () => {
  // 调用点的正确写法是传玩家看得见的名字；这一条钉住"传错就红"，而不是传错就印给玩家
  assert.throws(() => assertPlayerReadable('误传 id', pickUnavailable('stage_03')), /内部 id/)
  assert.throws(() => assertPlayerReadable('误传 id', pickUnavailable('rpt_1024')), /内部 id/)
})
