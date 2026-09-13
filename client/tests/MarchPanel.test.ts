/**
 * 职责：行军列表的展示映射与动作资格测试（B07 验收 7）。
 * 依赖：node:test / node:assert。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  actionForStatus, buildMarchPanel, formatDuration, marchActionText, marchRemainingText,
} from '../assets/scripts/game/world/MarchPanel'
import type { MarchRender } from '../assets/scripts/game/world/WorldViewModel'

function march(overrides: Partial<MarchRender> = {}): MarchRender {
  return {
    marchId: 'm1',
    from: { x: 10, y: 10 },
    to: { x: 40, y: 50 },
    x: 20,
    y: 30,
    status: 'MARCHING',
    action: 'GATHER',
    targetType: 'RESOURCE',
    rallyId: null,
    progress: 0.3,
    remainingMs: 125_000,
    gatherRemainingMs: 0,
    load: 20,
    loadCap: 200,
    corrected: false,
    ...overrides,
  }
}

test('行军中且目标为资源时给召回入口，时间显示到达倒计时', () => {
  const row = buildMarchPanel([march()])[0]
  assert.equal(row?.action, 'recall')
  assert.equal(row?.actionText, '召回')
  assert.equal(row?.title, '采集 · 目标 (40, 50)')
  assert.equal(row?.detail, '行军中 · 当前 (20, 30) · 载重 20/200')
  assert.equal(row?.remainingText, '2分5秒后到达')
})

test('采集中给收取入口，采满时显示可收取', () => {
  const row = buildMarchPanel([march({
    status: 'GATHERING',
    remainingMs: 0,
    gatherRemainingMs: 8_000,
  })])[0]
  assert.equal(row?.action, 'collect')
  assert.equal(row?.actionText, '收取')
  assert.equal(row?.remainingText, '8秒后采满')

  const full = marchRemainingText(march({
    status: 'GATHERING',
    remainingMs: 0,
    gatherRemainingMs: 0,
  }))
  assert.equal(full, '已可收取')
})

test('返程与交战中不提供动作入口，避免摆一个必然被服务端拒绝的按钮', () => {
  assert.equal(actionForStatus('RETURNING'), null)
  assert.equal(actionForStatus('FIGHTING'), null)
  assert.equal(buildMarchPanel([march({ status: 'RETURNING', remainingMs: 3_000 })])[0]?.action, null)
  assert.equal(buildMarchPanel([march({ status: 'RETURNING', remainingMs: 3_000 })])[0]?.remainingText, '3秒后回城')
})

test('列表最多展示请求上限，不把面板撑出屏幕', () => {
  const rows = buildMarchPanel([
    march({ marchId: 'm1' }),
    march({ marchId: 'm2' }),
    march({ marchId: 'm3' }),
    march({ marchId: 'm4' }),
  ], 3)
  assert.deepEqual(rows.map(row => row.marchId), ['m1', 'm2', 'm3'])
  assert.deepEqual(buildMarchPanel([], 3), [])
})

test('动作与状态文案保留未知枚举，不静默变成空白', () => {
  assert.equal(marchActionText('UNKNOWN' as never), 'UNKNOWN')
  assert.equal(formatDuration(3_661_000), '1小时1分')
  assert.equal(formatDuration(-1), '0秒')
})
