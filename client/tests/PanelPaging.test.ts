/**
 * 职责：面板行区分页算术的用例（B26 S10）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯三条：页数怎么算、越界怎么夹、区间半开不越界 —— 这三条错了的表现是
 * "玩家翻到一页空白"或"最后一行永远看不到"，正是这一格要修的东西。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { clampPage, pageNotice, pageCount, pageWindow } from '../assets/scripts/game/ui/PanelPaging'

test('空列表算 1 页：0 页会让页码显示成 1/0 并让"上一页"除零', () => {
  assert.equal(pageCount(0, 5), 1)
  assert.equal(pageCount(1, 5), 1)
  assert.equal(pageCount(5, 5), 1)
  assert.equal(pageCount(6, 5), 2)
  assert.equal(pageCount(12, 5), 3)
})

test('每页 0 行不可能出现：兜成 1，免得死循环或 NaN 页', () => {
  assert.equal(pageCount(7, 0), 7)
  assert.deepEqual(pageWindow(7, 0, 0), { start: 0, end: 1 })
})

test('页码越界一律夹回：换页签、少了一行之后不许停在空白页上', () => {
  assert.equal(clampPage(9, 12, 5), 2)
  assert.equal(clampPage(-3, 12, 5), 0)
  assert.equal(clampPage(1, 3, 5), 0, '总共 3 条只有一页时，第 2 页不存在')
})

test('区间是半开的、且不越界：直接喂给 slice 就能用', () => {
  assert.deepEqual(pageWindow(12, 0, 5), { start: 0, end: 5 })
  assert.deepEqual(pageWindow(12, 2, 5), { start: 10, end: 12 })
  assert.deepEqual(pageWindow(6, 1, 5), { start: 5, end: 6 })
})

test('页码那句话从 1 开始数：玩家不数 0', () => {
  assert.equal(pageNotice(0, 3), '第 1/3 页')
  assert.equal(pageNotice(2, 3), '第 3/3 页')
})
