/**
 * 职责：`game/ui/TruncatedList.ts` 的文案契约 —— 一屏画不下时到底对玩家说了什么。
 * 依赖：node:test / node:assert。
 *
 * <p>为什么值得钉：这句话原先在 7 个视图里各写一遍，七处都带着
 * 「（长列表需要 ScrollView，属编辑器资产）」—— 工程术语印在玩家脸上，而它不影响任何功能，
 * 单测/类型检查/构建/真跑全都绿，只有人眼能发现（收口清单 #220）。
 * 现在句子只有一个产地，所以把它"不许再长出内部词汇"写成断言，另有一道门
 * `scripts/check-player-copy-jargon.sh` 盯着视图层不许再自己拼这类句子。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { truncatedNotice } from '../assets/scripts/game/ui/TruncatedList'

const JARGON = ['编辑器', 'ScrollView', '服务端', '客户端', '下发', '字段',
  '幂等', '契约', '不变量', '定点数', '占位', '未实现', 'TODO', '接口']

test('没有隐藏项时返回空串，而不是"另有 0 项未显示"这种废话', () => {
  assert.equal(truncatedNotice('项', 0), '')
  assert.equal(truncatedNotice('项', -1), '')
})

test('有隐藏项时只说数量与量词，不带任何内部机制词汇', () => {
  const text = truncatedNotice('项', 2)
  assert.equal(text, '另有 2 项未显示')
  const hit = JARGON.find((word) => text.includes(word))
  assert.equal(hit, undefined, `玩家文案里出现了「${hit}」：${text}`)
})

test('量词由调用方给：各面板的名词不同，句子结构必须一致', () => {
  assert.equal(truncatedNotice('场', 1), '另有 1 场未显示')
  assert.equal(truncatedNotice('封', 3), '另有 3 封未显示')
  assert.equal(truncatedNotice('条', 5), '另有 5 条未显示')
  assert.equal(truncatedNotice('关', 8), '另有 8 关未显示')
  assert.equal(truncatedNotice('个目标', 12), '另有 12 个目标未显示')
})
