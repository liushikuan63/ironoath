/**
 * 职责：战力明细面板与目标列表的单测 —— B08 §1（UI 必须能点开看明细）与禁止项（不要在客户端做战力校验）。
 * 依赖：node:test / node:assert。引擎无关，可脱离 Cocos 运行（B00 铁律 2）。
 *
 * <p>本类钉住的三件事，都是「不会让任何其它测试变红、但会让玩家觉得游戏在骗他」的类型：
 * <ol>
 *   <li>明细五项之和必须等于展示战力（服务端有同样的断言，两端各钉一次）。</li>
 *   <li>峰值记忆生效时必须解释，且解释里<b>不含客户端自己写死的比率</b> ——
 *       那个数字属于 global 表的 PEAK_POWER_MEMORY_RATIO，调参后写死的文案就会撒谎。</li>
 *   <li>目标列表必须与服务端下发的一一对应：客户端不过滤、不重排、不判定能不能打。</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import * as PowerPanel from '../assets/scripts/game/power/PowerPanel'
import { buildPowerPanel, buildTargetRows, formatPower, formatRatio, targetSearchNotice } from '../assets/scripts/game/power/PowerPanel'
import type { PowerDetailResp } from '../assets/scripts/net/generated/Protocol'
import type { SearchTargetsResp, TargetBrief } from '../assets/scripts/net/generated/WorldProtocol'

function detail(overrides: Partial<PowerDetailResp> = {}): PowerDetailResp {
  return {
    power: { displayPower: 13500, matchPower: 9000, peakPower: 11000 },
    currentMatchPower: 9000,
    peakMemoryFloor: 8800,
    breakdown: { building: 5000, troops: 4000, heroes: 3000, tech: 1000, equipment: 500 },
    serverNow: 1_800_000_000_000,
    ...overrides,
  }
}

function target(overrides: Partial<TargetBrief> = {}): TargetBrief {
  return {
    id: 'p1',
    name: '铁誓骑士',
    coord: { x: 260, y: 256 },
    matchPower: 12000,
    powerRatio: 12000,
    distanceBand: 'NEAR',
    resourceHint: 'NORMAL',
    isShielded: false,
    tyrannyLevel: null,
    ...overrides,
  }
}

function searchResp(targets: TargetBrief[]): SearchTargetsResp {
  return { targets, selfMatchPower: 10000, bandLower: 5000, bandUpper: 20000, serverNow: 1 }
}

// ---------- 数字格式化 ----------

test('formatPower 用截断而不是四舍五入：显示值永远不大于真实值', () => {
  assert.equal(formatPower(0), '0')
  assert.equal(formatPower(9999), '9999')
  assert.equal(formatPower(10000), '1万')
  assert.equal(formatPower(19999), '1.9万', '四舍五入会得到 2.0万，而真实值不到 2 万')
  assert.equal(formatPower(1234567), '123.4万')
  assert.equal(formatPower(100_000_000), '1亿')
  assert.equal(formatPower(199_999_999), '1.9亿')
  assert.equal(formatPower(1_234_567_890), '12.3亿')
})

test('formatPower 拒绝非整数与负数：上游把定点数还原成 double 时必须立刻暴露', () => {
  assert.throws(() => formatPower(1.5), /整数/)
  assert.throws(() => formatPower(-1), /负/)
})

test('formatRatio 把定点倍率渲染成 "1.5×"，不出现浮点尾巴', () => {
  assert.equal(formatRatio(10000), '1×')
  assert.equal(formatRatio(15000), '1.5×')
  assert.equal(formatRatio(20000), '2×')
  assert.equal(formatRatio(7100), '0.71×')
  assert.equal(formatRatio(2900), '0.29×', '0.29 用 double 除法会得到 0.28999999')
})

// ---------- 明细面板 ----------

test('明细五行顺序固定、之和精确等于展示战力', () => {
  const view = buildPowerPanel(detail())
  assert.deepEqual(view.lines.map((line) => line.label), ['建筑', '部队', '武将', '科技', '装备'])
  const sum = view.lines.reduce((acc, line) => acc + line.value, 0)
  assert.equal(sum, 13500)
  assert.equal(view.totalText, view.displayPowerText, '总计必须等于展示战力，否则玩家逐项相加对不上')
})

test('峰值记忆生效时必须解释，且解释里不含客户端写死的比率', () => {
  const view = buildPowerPanel(detail({
    power: { displayPower: 13500, matchPower: 8800, peakPower: 11000 },
    currentMatchPower: 6000,
    peakMemoryFloor: 8800,
  }))
  assert.equal(view.peakMemoryActive, true)
  assert.ok(view.peakMemoryHint.length > 0, '托底生效却不解释，玩家会以为战力算错了')
  assert.ok(view.peakMemoryHint.includes(formatPower(8800)),
    '解释里要给出托底值本身，玩家才能自己核对')
  assert.ok(!view.peakMemoryHint.includes('%'),
    '文案里不能出现写死的百分比：那个比率属于 global 表的 PEAK_POWER_MEMORY_RATIO，调参后文案就会撒谎')
})

test('峰值记忆没有生效时不显示解释（否则玩家会以为自己的战力被人为抬高了）', () => {
  const view = buildPowerPanel(detail({
    power: { displayPower: 13500, matchPower: 9000, peakPower: 11000 },
    currentMatchPower: 9000,
    peakMemoryFloor: 8800,
  }))
  assert.equal(view.peakMemoryActive, false)
  assert.equal(view.peakMemoryHint, '')
})

// ---------- 目标列表 ----------

test('目标列表与服务端下发一一对应：不过滤、不重排、不去重', () => {
  const targets = [
    target({ id: 'a', name: '甲', powerRatio: 19000 }),
    target({ id: 'b', name: '乙', powerRatio: 10000 }),
    // 刻意放一个「看起来超出圈层」的倍率：客户端不得因此把它剔掉。
    // 能不能打由服务端判定，客户端第二次判定只会带来「列表里有、点了却说打不了」
    target({ id: 'c', name: '丙', powerRatio: 21000 }),
    target({ id: 'a', name: '甲', powerRatio: 19000 }),
  ]
  const rows = buildTargetRows(searchResp(targets))
  assert.equal(rows.length, targets.length)
  assert.deepEqual(rows.map((row) => row.id), ['a', 'b', 'c', 'a'])
  assert.deepEqual(rows.map((row) => row.name), ['甲', '乙', '丙', '甲'])
})

test('档位与暴虐标签都翻译成中文，平民档保持 null 不显示无意义标签', () => {
  const rows = buildTargetRows(searchResp([
    target({ id: '1', distanceBand: 'NEAR', resourceHint: 'RICH', tyrannyLevel: null }),
    target({ id: '2', distanceBand: 'MID', resourceHint: 'NORMAL', tyrannyLevel: 'TYRANT' }),
    target({ id: '3', distanceBand: 'FAR', resourceHint: 'POOR', tyrannyLevel: 'BRUTE' }),
    target({ id: '4', tyrannyLevel: 'PUBLIC_ENEMY' }),
  ]))
  assert.deepEqual(rows.map((row) => row.distanceText), ['近', '中', '远', '近'])
  assert.deepEqual(rows.map((row) => row.resourceText), ['富庶', '一般', '贫瘠', '一般'])
  assert.equal(rows[0]?.tyrannyText, null)
  assert.equal(rows[1]?.tyrannyText, '强横')
  assert.ok(rows[2]?.tyrannyText?.includes('围剿'), '暴虐档要让人看出打他有额外好处')
  assert.ok(rows[3]?.tyrannyText?.includes('公敌'))
})

test('服务端发来未知暴虐档位时原样显示，而不是崩在 undefined 上', () => {
  const rows = buildTargetRows(searchResp([target({ tyrannyLevel: 'SOMETHING_NEW' })]))
  assert.equal(rows[0]?.tyrannyText, 'SOMETHING_NEW',
    '新档位是加协议时最容易漏翻译的地方；显示原文至少让玩家和客服能看出发生了什么')
})

test('坐标照实显示（客户端本来就能算距离，藏坐标没有意义）', () => {
  const rows = buildTargetRows(searchResp([target({ coord: { x: 12, y: 340 } })]))
  assert.equal(rows[0]?.coordText, '(12, 340)')
})

// ---------- 空态那句文案：三相都要钉 ----------

test('targetSearchNotice 只在「搜过且零行」那一相说话，另两相必须闭嘴', () => {
  // ① 还没搜过：`rows` 的初值就是空数组，光看行数分不出这一相与 ②，所以 searched 要单独传
  assert.equal(targetSearchNotice(false, 0), '',
    '搜索前就印「没有目标」，玩家按下搜索会以为自己在跟一面墙较劲')
  // ② 搜过了、确实一个没有：不说等于把「一片空行区」留给玩家自己猜
  assert.equal(targetSearchNotice(true, 0), '这一带没有可打的目标')
  // ③ 搜过且有行：有行还印这句等于自己打自己的脸
  assert.equal(targetSearchNotice(true, 3), '')
  assert.equal(targetSearchNotice(true, 1), '', '一页正好一条时也不算"没有目标"')
})

// ---------- 禁止项：客户端零校验 ----------

test('模块不导出任何「能不能打」的判定函数（B08 禁止项：不要在客户端做战力校验）', () => {
  const forbidden = /can|allow|check|valid|verify|judge|filter/i
  const offenders = Object.keys(PowerPanel).filter((name) => forbidden.test(name))
  assert.deepEqual(offenders, [],
    `客户端不得出现战力校验入口，实际导出了 ${offenders.join(', ')}。`
    + '校验只能在服务端：客户端的判定可以被改包绕过，而绕过之后服务端若没有独立校验就直接失效')
})
