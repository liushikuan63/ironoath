/**
 * 职责：署名清单与折行用例（V14；CC BY 3.0 的授权条件）。
 * 依赖：node:test + game/settings/Credits（不碰 cc）。
 *
 * <p><b>每条钉的都是一个会做错的地方</b>：
 * ① 那段署名原文**逐字**保留（改一个字就不是原作者给出的那份许可了）—— 这是本文件存在的头号理由；
 * ② "不要求署名"的 CC0 条目要写明不强制，否则玩家/审查者会把它当授权条件；
 * ③ 折行按**词**折，不把作者名劈成两半；超长词才硬切；
 * ④ `perLine` 非法时抛错而不是静默折出空列表。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { CREDITS, wrapCredit } from '../assets/scripts/game/settings/Credits'

test('Game-icons 那段署名原文逐字保留（CC BY 3.0 的授权条件）', () => {
  const entry = CREDITS.find((item) => item.key === 'game-icons')
  assert.ok(entry !== undefined, '没有 game-icons 这一条 —— CC BY 3.0 的素材就无权分发')
  assert.equal(entry.credit,
    'Icons made by Delapouite, Lorc, Skoll, Heavenly Dog, Sbed, Faithtoken, Andy Meneely from https://game-icons.net',
    '这一行必须与 art-src/ATTRIBUTION.md 的原文块逐字一致')
  assert.match(entry.license, /Creative Commons Attribution 3\.0/)
  assert.match(entry.credit, /https:\/\/game-icons\.net/, '来源链接不能省 —— CC BY 要求指出来源')
})

test('CC0 那一条写明「不要求署名」，不会被读成授权条件', () => {
  const kenney = CREDITS.find((item) => item.key === 'kenney')
  assert.ok(kenney !== undefined)
  assert.match(kenney.license, /CC0/)
  assert.match(kenney.note, /不要求署名/)
})

test('条目 key 唯一，且要求署名的排在最前', () => {
  const keys = CREDITS.map((item) => item.key)
  assert.equal(new Set(keys).size, keys.length, `key 重复：${keys.join(' / ')}`)
  assert.equal(keys[0], 'game-icons', '要求署名的必须排第一（登记备查的在后）')
})

test('折行按词折，不把作者名劈成两半', () => {
  const lines = wrapCredit('Icons made by Delapouite, Lorc, Skoll from https://game-icons.net', 30)
  assert.ok(lines.every((line) => Array.from(line).length <= 30),
    `有行超宽：${lines.map((l) => Array.from(l).length).join(',')}`)
  for (const name of ['Delapouite,', 'Lorc,', 'Skoll']) {
    assert.ok(lines.some((line) => line.includes(name)), `${name} 被折断了：${lines.join(' / ')}`)
  }
})

test('单个超长词按字符硬切（超长 URL 与中文长串）', () => {
  const long = 'x'.repeat(70)
  const lines = wrapCredit(long, 30)
  assert.deepEqual(lines.map((line) => line.length), [30, 30, 10])
  assert.equal(lines.join(''), long, '硬切不许丢字符')
  // 空串折出空列表（不是 ['']，那会在界面上多画一行空白）
  assert.deepEqual(wrapCredit('', 30), [])
})

test('perLine 非法就抛错：静默按 0 折会让两个字一行、把整页挤爆', () => {
  assert.throws(() => wrapCredit('abc', 0))
  assert.throws(() => wrapCredit('abc', 2.5))
})
