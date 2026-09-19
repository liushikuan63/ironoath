/**
 * 职责：外观页（B24 块③ 头像框）的纯逻辑用例。
 * 依赖：node:test + game/avatar/AvatarFramePanel（不碰 cc）。
 *
 * <p><b>这些用例盯的是三处最容易做假的地方</b>：① 未拥有的框**不能**给出一颗按钮
 * （点了必然报错，或者更糟 —— 造出「去商店」这种表里根本没有的指向）；
 * ② 「拥有」与「佩戴」是两位，卸下不等于失去；
 * ③ 颜色写坏了要退回中性色，不能画一个看不见的框（玩家会以为没戴上）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  avatarInitialOf, buildAvatarFramePanel, buildAvatarFrameRow, frameActionOf,
  normalizeFrameColor, wearBodyOf, wearResultText,
} from '../assets/scripts/game/avatar/AvatarFramePanel'
import type { AvatarFrameListResp, AvatarFrameView } from '../assets/scripts/net/generated/Protocol'

const NOW = 1_700_000_000_000

function frame(overrides: Partial<AvatarFrameView> = {}): AvatarFrameView {
  return {
    frameId: 'frame_season_s1', name: '赛季征战框', rarity: 'SR',
    placeholderColor: '#C8A24B', owned: false, worn: false, ...overrides,
  }
}

function resp(frames: AvatarFrameView[]): AvatarFrameListResp {
  return { frames, serverNow: NOW }
}

test('未拥有的框不给按钮：没有「在哪买」这一位，就不许编一个指向出来', () => {
  const row = buildAvatarFrameRow(frame({ owned: false, worn: false }))
  assert.equal(row.stateText, '未拥有')
  assert.equal(row.actionText, null)
  assert.equal(row.action, null)
  assert.equal(wearBodyOf(row), null, '没拥有的行发不出请求')
  assert.equal(frameActionOf(frame({ owned: false })), null)
})

test('拥有未佩戴 = 「已拥有」+「佩戴」；佩戴中 = 「佩戴中」+「卸下」', () => {
  const owned = buildAvatarFrameRow(frame({ owned: true, worn: false }))
  assert.equal(owned.stateText, '已拥有')
  assert.equal(owned.actionText, '佩戴')
  assert.deepEqual(wearBodyOf(owned), { frameId: 'frame_season_s1' })

  const worn = buildAvatarFrameRow(frame({ owned: true, worn: true }))
  assert.equal(worn.stateText, '佩戴中')
  assert.equal(worn.actionText, '卸下')
  assert.deepEqual(wearBodyOf(worn), { frameId: null }, '卸下 = frameId 为 null，不是"戴一个空框"')
})

test('「拥有」与「佩戴」是两位：卸下之后仍然是已拥有', () => {
  const panel = buildAvatarFramePanel(resp([frame({ owned: true, worn: false })]))
  assert.equal(panel.wornFrameId, null)
  assert.equal(panel.wornText, '未佩戴头像框')
  assert.equal(panel.rows[0]?.owned, true, '卸下不等于失去')
  assert.equal(panel.ownedCountText, '已拥有 1 / 1')
})

test('预览圈跟着佩戴走：戴了显示那枚的颜色与名字，没戴退回中性色', () => {
  const wearing = buildAvatarFramePanel(resp([
    frame({ frameId: 'frame_founder_n', name: '拓荒者框', rarity: 'N', placeholderColor: '#8C7A5B', owned: true }),
    frame({ owned: true, worn: true }),
  ]))
  assert.equal(wearing.previewColor, '#C8A24B')
  assert.equal(wearing.wornFrameId, 'frame_season_s1')
  assert.equal(wearing.wornText, '佩戴中：赛季征战框')
  assert.equal(wearing.ownedCountText, '已拥有 2 / 2')

  const bare = buildAvatarFramePanel(resp([frame({ owned: true, worn: false })]))
  assert.equal(bare.previewColor, '#6B6257')
})

test('颜色写坏了退回中性色（透明框看起来就是"没戴上"）', () => {
  assert.equal(normalizeFrameColor('#c8a24b'), '#C8A24B', '小写归一成大写，两边画的才是同一个色')
  assert.equal(normalizeFrameColor('red'), '#6B6257')
  assert.equal(normalizeFrameColor('#C8A24'), '#6B6257')
  assert.equal(normalizeFrameColor(null), '#6B6257')
  assert.equal(normalizeFrameColor(undefined), '#6B6257')
  assert.equal(buildAvatarFrameRow(frame({ placeholderColor: '' })).color, '#6B6257')
})

test('一枚都没有时才说那句引导；已经有框的人不再被教育一次', () => {
  const empty = buildAvatarFramePanel(resp([frame({ owned: false })]))
  assert.match(empty.emptyText ?? '', /还没有拿到任何头像框/)
  assert.equal(empty.ownedCountText, '已拥有 0 / 1')

  const hasOne = buildAvatarFramePanel(resp([frame({ owned: true })]))
  assert.equal(hasOne.emptyText, null)
})

test('列表还没拉回来时画一句说明，不画一个空列表假装"你什么都没有"', () => {
  const panel = buildAvatarFramePanel(null, '张三')
  assert.deepEqual(panel.rows, [])
  assert.equal(panel.noticeText, '外观列表还没拉回来，稍后再试')
  assert.equal(panel.emptyText, null, '拉失败与"确实没有"是两件事')
  assert.equal(panel.initialText, '张')
})

test('预览头像取昵称首字：空昵称退回「君」，emoji 不被劈成半个', () => {
  assert.equal(avatarInitialOf('张三'), '张')
  assert.equal(avatarInitialOf('  '), '君')
  assert.equal(avatarInitialOf(''), '君')
  assert.equal(avatarInitialOf('😀无敌'), '😀', '按码点取，不按 UTF-16 码元')
})

test('操作之后的话取服务端回执里的名字（本地那份表可能已经过期）', () => {
  assert.equal(wearResultText([frame({ name: '赛季征战框', owned: true, worn: true })]),
    '已戴上「赛季征战框」')
  assert.equal(wearResultText([frame({ owned: true, worn: false })]), '已卸下头像框')
})
