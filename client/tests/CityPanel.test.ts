/**
 * 职责：CityPanel 的单测 —— B03 §2（结构化错误）、§4（本地倒计时）、验收 3（进度不越界）、
 * 验收 7（队列显示）。
 * 依赖：node:test / node:assert。
 *
 * <p>重点盯两条：
 * <ol>
 *   <li><b>倒计时绝不为负</b>（协议明写的禁止项）。到点之后停在 0 并提示可收割</li>
 *   <li><b>本地时钟不能影响升级进度</b>（铁律 5）。玩家把手机时间调快一小时，
 *       升级就该真的还要一小时 —— 这条只能靠 TimeSync 的 offset 参与换算来保证</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildBuildingRow, buildCityPanel, collectMessage, countdownMs, errorText, formatCountdown,
  formatPercent, outputText, queueExpandText, queueText,
} from '../assets/scripts/game/city/CityPanel'
import type { BuildingView, CityListResp, QueueView, ResourceStateView } from '../assets/scripts/net/generated/CityProtocol'

const HOUR = 3600_000

function building(overrides: Partial<BuildingView> = {}): BuildingView {
  return {
    id: 'b1',
    configId: 'building_wood',
    level: 6,
    gridX: 1,
    gridY: 2,
    status: 'IDLE',
    finishAt: null,
    remainingSeconds: null,
    progress: 10000,
    // 非升级中：与协议一致，两个推进字段都是 0（回退到 progress 快照）
    startedAt: 0,
    totalSeconds: 0,
    helpCount: 0,
    ...overrides,
  }
}

function resource(current: number, cap: number, perHour = 100): ResourceStateView {
  return { current, cap, protectedAmount: 0, perHour, lastSettle: 0 }
}

function cityResp(buildings: BuildingView[], queues: QueueView,
                  resources: Partial<Record<string, ResourceStateView>> = {}): CityListResp {
  return {
    buildings,
    queues,
    resources: resources as unknown as CityListResp['resources'],
    serverNow: 0,
  }
}

const QUEUE: QueueView = { used: 1, available: 2, max: 4 }

// ---------- 倒计时（B03 §4） ----------

test('倒计时用 finishAt + TimeSync 偏移换算：本地时钟快一小时也不会让升级立刻完成', () => {
  const finishAt = 10 * HOUR
  // 玩家把手机时间调快了一小时 ⇒ offsetMs = -1 小时（服务端时刻 - 本地时刻）
  assert.equal(countdownMs(finishAt, -HOUR, 9 * HOUR), 2 * HOUR,
    '本地 9 点其实是服务端 8 点，距 10 点还有 2 小时')
  // 反过来，本地慢一小时
  assert.equal(countdownMs(finishAt, HOUR, 9 * HOUR), 0,
    '本地 9 点其实是服务端 10 点，已经到点了')
})

test('倒计时绝不为负（协议明写的禁止项），到点后停在 0', () => {
  assert.equal(countdownMs(1000, 0, 5000), 0)
  assert.equal(countdownMs(1000, 0, 1000), 0)
  assert.equal(countdownMs(1000, 0, 999), 1)
  assert.equal(countdownMs(null, 0, 0), null, '不在升级中就没有倒计时')
  assert.equal(formatCountdown(0), '可收割')
  assert.equal(formatCountdown(-5000), '可收割', '负数也必须落到同一个提示，绝不能显示 -5 秒')
})

test('倒计时文本按 时/分/秒 分档，个位数补零', () => {
  assert.equal(formatCountdown(3_600_000), '1小时00分00秒')
  assert.equal(formatCountdown(3_723_000), '1小时02分03秒')
  assert.equal(formatCountdown(123_000), '02分03秒')
  assert.equal(formatCountdown(999), '00分00秒', '不足一秒显示 0 秒，不显示毫秒')
})

test('升级中的建筑显示倒计时与进度；空闲建筑两者都不显示', () => {
  const upgrading = buildBuildingRow(building({
    status: 'UPGRADING', finishAt: 10_000, progress: 5000,
  }), 0, 5_000)
  assert.equal(upgrading.upgrading, true)
  assert.equal(upgrading.countdownText, '00分05秒')
  assert.equal(upgrading.progressText, '50%')
  assert.equal(upgrading.collectable, false)

  const idle = buildBuildingRow(building(), 0, 5_000)
  assert.equal(idle.countdownText, null)
  assert.equal(idle.progressText, null)
  assert.equal(idle.statusText, '空闲')
})

test('协议里没有 FINISHED 状态：到点的建筑仍是 UPGRADING，靠倒计时归零判定可收割', () => {
  const done = buildBuildingRow(building({
    status: 'UPGRADING', finishAt: 10_000, progress: 10000,
  }), 0, 20_000)
  assert.equal(done.upgrading, true, '状态仍是升级中 —— 要等 /city/collect 才 +1 级并回到 IDLE')
  assert.equal(done.collectable, true)
  assert.equal(done.statusText, '已完成，待收割')
  assert.equal(done.countdownText, '可收割')

  const notYet = buildBuildingRow(building({
    status: 'UPGRADING', finishAt: 10_000, progress: 5000,
  }), 0, 5_000)
  assert.equal(notYet.collectable, false)
  assert.equal(notYet.statusText, '升级中')
})

test('PAUSED 不显示倒计时：服务端对暂停建筑的 remainingSeconds 恒为 0，照 finishAt 算会显示一个永远不走的表', () => {
  const paused = buildBuildingRow(building({
    status: 'PAUSED', finishAt: 10_000, progress: 3000,
  }), 0, 5_000)
  assert.equal(paused.paused, true)
  assert.equal(paused.upgrading, false)
  assert.equal(paused.countdownText, null)
  assert.equal(paused.collectable, false)
  assert.match(paused.statusText, /已暂停/)
})

test('level 显示的是已达成的等级：升级途中不显示目标等级，否则玩家会以为已经拿到新等级的产量', () => {
  const row = buildBuildingRow(building({ level: 6, status: 'UPGRADING', finishAt: 10_000 }), 0, 0)
  assert.equal(row.title, 'building_wood Lv6')
})

test('倒计时与百分比必须同步推进：进度不能停在响应那一刻的快照上', () => {
  // 服务端在 t=0 下发：20 秒的升级刚开始（progress 快照 = 0）
  const resp = building({ status: 'UPGRADING', finishAt: 20_000, progress: 0, startedAt: 0, totalSeconds: 20 })

  const atFive = buildBuildingRow(resp, 0, 5_000)
  assert.equal(atFive.countdownText, '00分15秒')
  assert.equal(atFive.progressText, '25%', '本地过 5 秒 ⇒ 25%，与服务端公式 elapsed/total 同源')

  const atTen = buildBuildingRow(resp, 0, 10_000)
  assert.equal(atTen.countdownText, '00分10秒')
  assert.equal(atTen.progressText, '50%')

  const atNineteen = buildBuildingRow(resp, 0, 19_999)
  assert.equal(atNineteen.progressText, '99%', '封顶 99%，到点前不说 100%')

  const done = buildBuildingRow(resp, 0, 20_000)
  assert.equal(done.progressText, '99%', '到点仍不报 100%：先收割，等级才会真的 +1')
  assert.equal(done.collectable, true)
})

test('推进字段缺失/非法时回退到快照，绝不把面板炸掉（滚动升级期间新旧并存）', () => {
  const legacy = building({ status: 'UPGRADING', finishAt: 10_000, progress: 4_200 })
  // 模拟旧服务端：JSON 里根本没有这两个字段
  const raw = { ...legacy } as Record<string, unknown>
  delete raw.startedAt
  delete raw.totalSeconds
  const row = buildBuildingRow(raw as unknown as BuildingView, 0, 5_000)
  assert.equal(row.progressText, '42%', '回退到服务端快照，而不是 NaN 或抛异常')
})

test('总时长为 0 时回退到服务端快照（旧数据/非升级中），不凭空造进度', () => {
  const row = buildBuildingRow(building({ status: 'UPGRADING', finishAt: 10_000, progress: 5_000 }), 0, 5_000)
  assert.equal(row.progressText, '50%')
})

test('收割响应分两态：没有建筑升级时绝不报「升级完成」', () => {
  assert.equal(collectMessage({ collected: [], output: [], serverNow: 0 }), null,
    '既没升级也没产出 ⇒ 什么都不说')
  const onlyOutput = collectMessage({
    collected: [], output: [{ type: 'WOOD', amount: 800 }], serverNow: 0,
  })
  assert.equal(onlyOutput?.kind, 'output')
  assert.equal(onlyOutput?.text, '补结算产出 WOOD +800',
    '一键收割常常只结算离线产出 —— 这种时候报「升级完成」就是假成功')

  const done = collectMessage({
    collected: [building({ level: 7 })], output: [], serverNow: 0,
  })
  assert.equal(done?.kind, 'done')
  assert.equal(done?.text, '升级完成 building_wood Lv7')
})
// ---------- 进度（验收 3） ----------

test('进度截断而不是四舍五入，且封顶 99%：进度条走到 100% 而升级没完成，玩家会点按钮然后发现点不动', () => {
  assert.equal(formatPercent(0), 0)
  assert.equal(formatPercent(5000), 50)
  assert.equal(formatPercent(9999), 99)
  assert.equal(formatPercent(10000), 99, '协议明写不会越界，但客户端仍然封顶，绝不显示 100%')
  assert.equal(formatPercent(199), 1, '截断：1.99% 显示成 1%')
  assert.throws(() => formatPercent(1.5), /定点整数/)
})

// ---------- 结构化错误（B03 §2） ----------

test('结构化错误拼成「需要 X，当前 Y」，绝不显示笼统的「条件不足」', () => {
  assert.equal(errorText({ need: '木材 12000', current: '木材 3400' }, '条件不足'),
    '需要 木材 12000，当前 木材 3400')
  assert.equal(errorText({ need: '主城 8 级', current: '主城 6 级' }, '条件不足'),
    '需要 主城 8 级，当前 主城 6 级')
})

test('need 或 current 缺失/为空时退化到 fallback，但仍然要说点什么', () => {
  assert.equal(errorText(null, '升级失败：队列已满'), '升级失败：队列已满')
  assert.equal(errorText({ need: '', current: '木材 3400' }, '条件不足'), '条件不足')
  assert.equal(errorText({ need: '木材 12000', current: '' }, '条件不足'), '条件不足')
})

// ---------- 队列（验收 7） ----------

test('队列同时显示已用/可用与「可开启第 N 条」；已到上限时不再提示', () => {
  assert.equal(queueText(QUEUE), '建造队列 1/2')
  assert.equal(queueExpandText(QUEUE), '可开启第 3 条队列（上限 4）')
  assert.equal(queueExpandText({ used: 4, available: 4, max: 4 }), null)

  const panel = buildCityPanel(cityResp([], QUEUE), 0, 0)
  assert.equal(panel.queueText, '建造队列 1/2')
  assert.equal(panel.queueExpandText, '可开启第 3 条队列（上限 4）')
})

// ---------- 面板汇总 ----------

test('满仓资源要标出来：产出停了而玩家不知道，他会以为产量被偷偷改了', () => {
  const panel = buildCityPanel(cityResp([], QUEUE, {
    WOOD: resource(12000, 12000),
    STONE: resource(300, 12000),
  }), 0, 0)
  assert.deepEqual(panel.resourceLines, ['WOOD 12000/12000（已满，停产）', 'STONE 300/12000'])
})

test('有建筑到点未收割时，顶部给出收割提示并说明收割同时结算离线产出', () => {
  const panel = buildCityPanel(cityResp([
    building({ id: 'a', status: 'UPGRADING', finishAt: 1000 }),
    building({ id: 'b', status: 'UPGRADING', finishAt: 2000 }),
    building({ id: 'c', status: 'IDLE' }),
  ], QUEUE), 0, 5000)
  assert.equal(panel.collectHint, '2 个建筑已升级完成，点击收割（收割同时结算离线产出）')
  assert.equal(panel.rows.length, 3)

  const none = buildCityPanel(cityResp([building({ status: 'IDLE' })], QUEUE), 0, 0)
  assert.equal(none.collectHint, null)
})

test('收割产出为空时不显示「补结算产出」这一行', () => {
  assert.equal(outputText([]), null)
  assert.equal(outputText([{ type: 'WOOD', amount: 800 }, { type: 'GRAIN', amount: 200 }]),
    '补结算产出 WOOD +800 · GRAIN +200')
})

test('帮助次数为 0 时不显示（「已获帮助 0 次」只是噪音）', () => {
  assert.equal(buildBuildingRow(building({ helpCount: 0 }), 0, 0).helpText, null)
  assert.equal(buildBuildingRow(building({ helpCount: 3 }), 0, 0).helpText, '已获帮助 3 次')
})
