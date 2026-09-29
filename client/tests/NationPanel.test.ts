/**
 * 职责：国家面板纯逻辑用例（B13 · V13-S1：入籍 + 国库）。
 * 依赖：node:test + node:fs + game/nation/NationPanel（不碰 cc）。
 *
 * <p>**每条钉的都是一个会做错的地方**：
 * ① 无国家时必须同时给「创建」与「可加入列表」两条路，且其余三颗键灰掉并写明为什么；
 * ② 有国家时概况逐字段等于服务端下发值（客户端不许自己算等级/容量/余额）；
 * ③ 官职枚举原文（`GENERAL`/`KING`）与玩家 id 绝不上屏 —— 契约要求客户端查本地化表；
 * ④ 国库流水四要素齐（谁/何时/支给谁/多少）+ 余额自证连贯那一列必须在；
 * ⑤ 权限由服务端下发字段判：`kingId` 与 `myOffice`。国王那才亮解散；
 *    没有官职那颗支出键灰掉，**点了零请求**；
 * ⑥ 支给对象的两种形态（`player:<id>` / `sink:<用途>`）都换成人话，换不掉也不许印前缀；
 * ⑦ 流水被一屏截断时要报出截掉了几条（"只显示最近 5 条 + 另有 N 条未显示"是 #450~#452 那一族）；
 * ⑧ 全源码不出现 `Date.now` / `new Date` —— 国库时间只有一个来源（`serverNow`）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  OFFICE_LABELS, PLAIN_PAYEE_LABELS, SINK_LABELS, SPEND_AMOUNT_PRESETS, TREASURY_LOG_ROWS,
  UNKNOWN_MEMBER, UNKNOWN_OFFICE, UNKNOWN_OPERATOR, UNKNOWN_PAYEE, UNKNOWN_SINK,
  amountText, buildNationPanel, buildSpendRow, buildTreasuryRow, cooldownText, officeLabel, operatorLabel,
  payeeLabel, spendDraftBlocker, spendSinkOptions,
} from '../assets/scripts/game/nation/NationPanel'
import type { NationPanelInput, NationPanelView, SpendDraft } from '../assets/scripts/game/nation/NationPanel'
import {
  APPOINTABLE_OFFICES, DIPLOMACY_OPTIONS, buildAppointRows, buildDiplomacyRows, buildNationSections,
  buildNationTechSection, diplomacyLabel, diplomacyNotice, nationTechBlockText,
} from '../assets/scripts/game/nation/NationSections'
import type { NationRelationView } from '../assets/scripts/net/generated/NationProtocol'
import type { NationTechListView, NationTechView } from '../assets/scripts/net/generated/NationTechProtocol'
import type {
  NationTreasuryResp, NationTreasurySpendResp, NationView, TreasuryLogView,
} from '../assets/scripts/net/generated/NationProtocol'

const NOW = 1_800_000_000_000
const KING_ID = 'player_king'
const OTHER_ID = 'player_other'

function nation(overrides: Partial<NationView> = {}): NationView {
  return {
    nationId: 'nation_abc',
    name: '赤壁盟',
    kingId: KING_ID,
    level: 3,
    allianceCount: 2,
    memberCap: 4,
    capitalX: 120,
    capitalY: 88,
    treasury: 1_234_567,
    treasuryCap: 10_000_000,
    myOffice: 'GENERAL',
    serverNow: NOW,
    ...overrides,
  }
}

function log(overrides: Partial<TreasuryLogView> = {}): TreasuryLogView {
  return {
    at: NOW - 3 * 60_000,
    operatorId: KING_ID,
    counterparty: 'player:player_x',
    amount: 5_000,
    reason: '本周俸禄',
    balanceAfter: 1_229_567,
    ...overrides,
  }
}

function treasuryResp(overrides: Partial<NationTreasuryResp> = {}): NationTreasuryResp {
  return { balance: 1_234_567, logs: [], serverNow: NOW, ...overrides }
}

const NAMES = new Map<string, string>([[KING_ID, '赵国王'], [OTHER_ID, '钱谋士'], ['player_x', '孙俸禄']])

function input(overrides: Partial<NationPanelInput> = {}): NationPanelInput {
  return {
    nation: null,
    treasury: null,
    candidates: [],
    playerId: KING_ID,
    memberNames: NAMES,
    notice: null,
    ...overrides,
  }
}

/** 取 summary 里那一行的整段文字（找不到就报出全部行，免得"断言失败"看不出是哪一行不见了）。 */
function summaryText(view: NationPanelView, key: string): string {
  const line = view.summary.find(item => item.key === key)
  assert.ok(line !== undefined, `summary 少了一行 ${key}；现有：${view.summary.map(s => s.key).join(',')}`)
  return line.text
}

test('无国家：同时给「创建」与「可加入列表」两条路，其余三颗键灰且写明原因', () => {
  const view = buildNationPanel(input({
    candidates: [{ nationId: 'nation_x', name: '北伐营' }],
  }))
  assert.equal(view.mode, 'NONE')
  assert.equal(view.found.enabled, true)
  assert.equal(view.found.text, '创建国家')
  assert.equal(view.candidates.length, 1)
  assert.equal(view.candidates[0]?.name, '北伐营')
  assert.equal(view.candidates[0]?.enabled, true, '能不能加入由服务端判（冷却/名额），客户端不预判')
  assert.notEqual(view.candidatesNotice, null)
  for (const key of ['leave', 'disband', 'spend'] as const) {
    assert.equal(view[key].enabled, false, `${key} 在无国家时必须灰`)
    assert.equal(view[key].reason, '你还不在任何国家里', `${key} 灰了要写明为什么`)
  }
  // 有国家之后「创建」那颗也要反着灰掉：两态的键集合必须一样，表现层才能一张表画完
  const inNation = buildNationPanel(input({ nation: nation(), treasury: treasuryResp() }))
  assert.equal(inNation.found.enabled, false)
  assert.equal(inNation.found.reason, '你已经在一个国家里')
})

test('可加入列表为空：说一句"本服还没有国家"，不是一片空白', () => {
  const view = buildNationPanel(input())
  assert.equal(view.candidates.length, 0)
  assert.match(String(view.candidatesNotice), /还没有国家/)
})

test('有国家：概况逐字段等于服务端下发值，客户端不重算', () => {
  const view = buildNationPanel(input({ nation: nation(), treasury: treasuryResp() }))
  assert.equal(view.mode, 'MEMBER')
  assert.equal(view.title, '赤壁盟')
  assert.equal(summaryText(view, 'name'), '国名：赤壁盟')
  assert.equal(summaryText(view, 'level'), '国家等级：Lv3')
  assert.equal(summaryText(view, 'member'), '成员联盟：2 / 4')
  assert.equal(summaryText(view, 'treasury'), '国库：1,234,567 / 10,000,000')
  assert.equal(summaryText(view, 'capital'), '都城：120, 88')
  assert.equal(summaryText(view, 'office'), '我的官职：大将军')
  // 服务端给 9 级 / 1 / 9，屏上不许还写着上一份的数
  const other = buildNationPanel(input({ nation: nation({ level: 9, allianceCount: 1, memberCap: 9 }) }))
  assert.equal(summaryText(other, 'level'), '国家等级：Lv9')
  assert.equal(summaryText(other, 'member'), '成员联盟：1 / 9')
})

test('官职：枚举原文绝不上屏，查不到给「未知官职」，没官职说「无官职」', () => {
  assert.equal(officeLabel('GENERAL'), '大将军')
  assert.equal(officeLabel('KING'), '国王')
  assert.equal(officeLabel(null), '无官职')
  assert.equal(officeLabel(''), '无官职')
  assert.equal(officeLabel('OFFICE_FROM_THE_FUTURE'), UNKNOWN_OFFICE)
  assert.deepEqual(Object.keys(OFFICE_LABELS).sort(), ['DIPLOMAT', 'GENERAL', 'KING', 'MINISTER', 'PRIME_MINISTER', 'REPRESENTATIVE'])
  assert.deepEqual(Object.keys(SINK_LABELS).sort(), ['NATIONAL_TECH', 'WAR_BOOST'])
  const view = buildNationPanel(input({ nation: nation({ myOffice: 'OFFICE_FROM_THE_FUTURE' }), treasury: treasuryResp() }))
  const printed = [view.headline, ...view.summary.map(s => s.text)].join(' ')
  for (const raw of ['GENERAL', 'OFFICE_FROM_THE_FUTURE', 'nation_abc', 'player_king']) {
    assert.ok(!printed.includes(raw), `屏上出现了裸值 ${raw}：${printed}`)
  }
})

test('国库：余额、上限与流水四要素齐，余额自证那一列必须在', () => {
  const view = buildNationPanel(input({
    nation: nation(),
    treasury: treasuryResp({ logs: [log()] }),
  }))
  const box = view.treasury
  assert.ok(box !== null)
  assert.equal(box.balanceText, '1,234,567')
  assert.equal(box.capText, '10,000,000')
  assert.equal(box.logs.length, 1)
  const row = box.logs[0]
  assert.ok(row !== undefined)
  assert.equal(row.headText, '孙俸禄 · 5,000', '支给谁与多少必须在主行')
  assert.equal(row.detailText, '3 分钟前 · 赵国王 · 本周俸禄', '何时与谁做的必须在副行')
  assert.equal(row.balanceText, '余额 1,229,567', '带上余额，流水才能自证连贯')
})

test('国库：支给对象的两种形态都换成人话，换不掉也不许印前缀或 id', () => {
  assert.equal(payeeLabel('player:player_x', NAMES), '孙俸禄')
  assert.equal(payeeLabel('player:player_ghost', NAMES), UNKNOWN_MEMBER)
  assert.equal(payeeLabel('sink:NATIONAL_TECH', NAMES), '国家科技')
  assert.equal(payeeLabel('sink:WAR_BOOST', NAMES), '国战增益')
  assert.equal(payeeLabel('sink:SINK_FROM_THE_FUTURE', NAMES), '其他用途')
  // **裸 token 走另一条回退语**（`UNKNOWN_PAYEE` = 「其他」）：这一条原先断言的是「其他用途」，
  // 而真链路的周税入账把那个口径的毛病照了出来 —— 一笔入账显示成"用途"读起来像钱花掉了
  assert.equal(payeeLabel('随便什么', NAMES), '其他')
  assert.equal(operatorLabel('system', NAMES), '系统')
  assert.equal(operatorLabel(KING_ID, NAMES), '赵国王')
  assert.equal(operatorLabel('player_ghost', NAMES), UNKNOWN_OPERATOR)

  const view = buildNationPanel(input({
    nation: nation(),
    treasury: treasuryResp({
      logs: [log({ operatorId: 'player_ghost', counterparty: 'player:player_ghost' })],
    }),
  }))
  const box = view.treasury
  assert.ok(box !== null)
  const printed = box.logs.map(row => `${row.headText} ${row.detailText} ${row.balanceText}`).join(' ')
  for (const raw of ['player:', 'sink:', 'player_ghost', 'system']) {
    assert.ok(!printed.includes(raw), `屏上出现了裸值 ${raw}：${printed}`)
  }
  assert.match(printed, /未知操作人/)
  assert.match(printed, new RegExp(UNKNOWN_MEMBER))
})

test('国库空账：说一句"还没有流水"，不是空白', () => {
  const view = buildNationPanel(input({ nation: nation(), treasury: treasuryResp() }))
  assert.match(String(view.treasury?.emptyText), /还没有流水/)
})

test('国库拿不到：那一块是 null（不是空账）—— 两者在界面上必须说得清不同', () => {
  const missing = buildNationPanel(input({ nation: nation(), treasury: null }))
  assert.equal(missing.treasury, null, '没拉到 ≠ 空账：一个是"读不到"，一个是"账上没流水"')
  const empty = buildNationPanel(input({ nation: nation(), treasury: treasuryResp() }))
  assert.notEqual(empty.treasury, null)
  assert.equal(empty.treasury?.logs.length, 0)
  assert.notEqual(empty.treasury?.emptyText, null)
})

test('流水超出屏：报出截掉了几条，不做"只显示最近 5 条 + 不告诉你还有"', () => {
  const logs = Array.from({ length: TREASURY_LOG_ROWS + 4 }, (_value, index) =>
    log({ at: NOW - index * 60_000 }))
  const view = buildNationPanel(input({ nation: nation(), treasury: treasuryResp({ logs }) }))
  const box = view.treasury
  assert.ok(box !== null)
  assert.equal(box.logs.length, TREASURY_LOG_ROWS)
  assert.equal(box.hiddenCount, 4, '截掉了几条必须报出来')
  assert.equal(box.emptyText, null)
  // 顺序照服务端（倒序），客户端不重排
  assert.equal(box.logs[0]?.detailText.split(' · ')[0], '刚刚')
})

test('权限：解散那颗只对国王亮；支出那颗按「有没有官职」亮', () => {
  const king = buildNationPanel(input({ nation: nation({ kingId: KING_ID, myOffice: 'KING' }), treasury: treasuryResp() }))
  assert.equal(king.disband.enabled, true)
  assert.equal(king.disband.reason, null)
  assert.equal(king.spend.enabled, true)
  assert.equal(king.spend.reason, null)

  const plain = buildNationPanel(input({ nation: nation({ kingId: OTHER_ID, myOffice: 'GENERAL' }), treasury: treasuryResp() }))
  assert.equal(plain.disband.enabled, false, '不是国王就不许亮解散')
  assert.equal(plain.disband.reason, '只有国王能解散这个国家')

  const noOffice = buildNationPanel(input({ nation: nation({ myOffice: null }), treasury: treasuryResp() }))
  assert.equal(noOffice.spend.enabled, false, '没有官职就不许亮国库支出')
  assert.equal(noOffice.spend.reason, '你在本国没有官职，按规定不能动国库')

  // 换一个人当国王，这一颗就该跟着换人 —— 结论完全来自下发字段
  const switched = buildNationPanel(input({
    nation: nation({ kingId: OTHER_ID, myOffice: 'MINISTER' }),
    playerId: OTHER_ID,
    treasury: treasuryResp(),
  }))
  assert.equal(switched.disband.enabled, true)
})

test('退出国：两态都亮得起，但文案要随处境换（无国家那颗根本画不出来）', () => {
  const view = buildNationPanel(input({ nation: nation(), treasury: treasuryResp() }))
  assert.equal(view.leave.enabled, true)
  assert.equal(view.leave.text, '退出国家')
})

test('支出回执：立刻显示这一笔，用回显的 payee 与 reason，不另算一遍', () => {
  const resp: NationTreasurySpendResp = {
    nationId: 'nation_abc',
    balance: 1_229_567,
    payee: 'player:player_x',
    amount: 5_000,
    reason: '本周俸禄',
    log: log({ at: NOW - 1_000, balanceAfter: 1_229_567 }),
    serverNow: NOW,
  }
  const row = buildSpendRow(resp, NAMES)
  assert.equal(row.headText, '孙俸禄 · 5,000')
  assert.equal(row.detailText, '刚刚 · 赵国王 · 本周俸禄')
  assert.equal(row.balanceText, '余额 1,229,567')
  // 另一笔发给"查不到的人"：不许回退成印 id
  const ghost = buildSpendRow({ ...resp, payee: 'player:player_ghost' }, NAMES)
  assert.equal(ghost.headText, `${UNKNOWN_MEMBER} · 5,000`)
  assert.ok(!ghost.headText.includes('player:'))
})

test('支出草稿：只卡协议明写的前置，不预判余额与本周限额', () => {
  const ok: SpendDraft = { amount: 10_000, reason: '本周俸禄', payeeType: 'PLAYER', payeeId: 'player_x', sink: null }
  assert.equal(spendDraftBlocker(ok), null, '齐全的一笔不该被客户端挡住')
  // 三个预设都必须是正整数（协议要求正整数，半笔俸禄比不发更难解释）
  assert.deepEqual([...SPEND_AMOUNT_PRESETS], [1_000, 10_000, 100_000])
  for (const amount of SPEND_AMOUNT_PRESETS) {
    assert.equal(spendDraftBlocker({ ...ok, amount }), null)
  }
  assert.match(String(spendDraftBlocker({ ...ok, amount: 0 })), /大于 0/)
  assert.match(String(spendDraftBlocker({ ...ok, amount: -1 })), /大于 0/)
  assert.match(String(spendDraftBlocker({ ...ok, amount: 1.5 })), /大于 0/)
  assert.match(String(spendDraftBlocker({ ...ok, reason: '  ' })), /用途不能为空/)
  assert.match(String(spendDraftBlocker({ ...ok, payeeId: null })), /选一个收这笔钱的人/)
  assert.match(String(spendDraftBlocker({ ...ok, payeeId: '' })), /选一个收这笔钱的人/)
  assert.match(String(spendDraftBlocker({ ...ok, payeeType: 'SINK', payeeId: null, sink: null })), /消耗性用途/)
  assert.equal(spendDraftBlocker({ ...ok, payeeType: 'SINK', payeeId: null, sink: 'NATIONAL_TECH' }), null)
  // 余额与限额客户端一概不查：给一个远大于余额的金额也不该在这里被挡
  assert.equal(spendDraftBlocker({ ...ok, amount: 999_999_999 }), null,
    '余额/限额只有服务端知道（13010/13011），客户端预判就是"显示能花、服务端却拒绝"')
  // 落点选项必须与协议枚举一一对应，且印出来的是中文
  assert.deepEqual(spendSinkOptions(), [
    { key: 'NATIONAL_TECH', label: '国家科技' },
    { key: 'WAR_BOOST', label: '国战增益' },
  ])
})

test('退国冷却：只用两个同源的服务端时刻相减，不引本机钟、不用本地配置', () => {
  const hour = 3_600_000
  assert.equal(cooldownText(NOW, NOW), '现在就可以再次入籍')
  assert.equal(cooldownText(NOW - 5_000, NOW), '现在就可以再次入籍', '冷却已过不要说"还差 0 小时"')
  assert.equal(cooldownText(NOW + hour, NOW), '约 1 小时后可以再次入籍')
  assert.equal(cooldownText(NOW + 23 * hour, NOW), '约 23 小时后可以再次入籍')
  assert.equal(cooldownText(NOW + 24 * hour, NOW), '约 1 天后可以再次入籍')
  assert.equal(cooldownText(NOW + 86_400_000, NOW), '约 1 天后可以再次入籍',
    'B13 的入籍冷却是 24 小时 —— 这条断言的作用是：改成别的时长时会被抓红，而不是悄悄显示 0')
  // 换一份 serverNow，同一个 cooldownUntil 必须给出不同的说法：说明它真的是在比时刻
  assert.notEqual(cooldownText(NOW + 86_400_000, NOW), cooldownText(NOW + 86_400_000, NOW + 40 * hour))
})

test('千分位：负数带号、非有限数给 0，不许把 NaN 印上屏', () => {
  assert.equal(amountText(0), '0')
  assert.equal(amountText(7), '7')
  assert.equal(amountText(999), '999')
  assert.equal(amountText(1_000), '1,000')
  assert.equal(amountText(1_234_567), '1,234,567')
  assert.equal(amountText(10_000_000), '10,000,000')
  assert.equal(amountText(-1_234_567), '-1,234,567')
  assert.equal(amountText(Number.NaN), '0')
  assert.equal(amountText(Number.POSITIVE_INFINITY), '0')
  // 小数不该出现：金额恒为最小单位整数，出现 ".5" 说明有人在中途乘过
  assert.ok(!amountText(1_234.5).includes('.'))
})

test('全源码不引本机时钟：国库时间只有 serverNow 一个来源', () => {
  const file = path.resolve('assets/scripts/game/nation/NationPanel.ts')
  const source = fs.readFileSync(file, 'utf8')
  assert.ok(!source.includes('Date.now'), 'NationPanel.ts 里出现了 Date.now —— 国库时间必须相对 serverNow 算')
  assert.ok(!/new Date\(/.test(source), 'NationPanel.ts 里出现了 new Date —— 同上')
})

// ---------- V13-S2：国家科技 / 外交 / 任命 ----------

function techRow(overrides: Partial<NationTechView> = {}): NationTechView {
  return {
    techId: 'nation_tech_wood', name: '林地开发', school: 'AGRICULTURE', effectAttr: 'WOOD_OUTPUT',
    effectValuePerLevelFixed: 400, level: 1, maxLevel: 5, requireNationLevel: 2,
    nextCostTreasury: 12_000, canResearch: true, blockedReason: 'NONE', ...overrides,
  }
}

function techResp(overrides: Partial<NationTechListView> = {}): NationTechListView {
  return {
    nationId: 'nation_abc', nationName: '赤壁盟', nationLevel: 3, treasury: 1_234_567,
    techs: [techRow()], serverNow: NOW, ...overrides,
  }
}

function relation(overrides: Partial<NationRelationView> = {}): NationRelationView {
  return { nationId: 'nation_x', nationName: '北伐营', relation: 'HOSTILE', ...overrides }
}

test('国家科技：动作与原因都照服务端那一位说，客户端不自己判等级与余额', () => {
  const ok = buildNationTechSection(techResp())
  assert.match(String(ok.headerText), /赤壁盟 · 国家等级 Lv3 · 国库 1,234,567/)
  assert.equal(ok.rows.length, 1)
  const row = ok.rows[0]
  assert.ok(row !== undefined)
  assert.equal(row.enabled, true)
  assert.equal(row.actionText, '研究一级')
  assert.equal(row.reason, null)
  assert.equal(row.detailText, 'Lv1/5 · 下一级 12,000')
  assert.equal(row.effectText, '木材产量 +4%/级')
  assert.ok(row.titleText.includes('林地开发') && row.titleText.includes('农政'),
    '名字与学派都要在行上（名字是服务端给的那一份）')

  // 服务端说研究不了时，按钮与原因都必须跟着变 —— 客户端不许自己算
  const blocked = buildNationTechSection(techResp({
    techs: [techRow({ canResearch: false, blockedReason: 'TREASURY_LOW' })],
  }))
  assert.equal(blocked.rows[0]?.enabled, false)
  assert.equal(blocked.rows[0]?.actionText, '国库余额不足')
  assert.equal(blocked.rows[0]?.reason, '国库余额不足')
  // 同一份数据，只把 blockedReason 换掉，结论必须跟着换（证它是照服务端说的）
  const limit = buildNationTechSection(techResp({
    techs: [techRow({ canResearch: false, blockedReason: 'OFFICER_LIMIT' })],
  }))
  assert.equal(limit.rows[0]?.reason, '本周国库支出额度已经用完，等下周')
  assert.notEqual(blocked.rows[0]?.reason, limit.rows[0]?.reason)

  // 满级：下一级花费不该再出现，效果值服务端没给就是 null
  const maxed = buildNationTechSection(techResp({
    techs: [techRow({ level: 5, canResearch: false, blockedReason: 'MAX_LEVEL', effectValuePerLevelFixed: null })],
  }))
  assert.equal(maxed.rows[0]?.detailText, 'Lv5/5')
  assert.equal(maxed.rows[0]?.effectText, null)
  assert.equal(maxed.rows[0]?.actionText, '已经满级')

  // 没拉到 ≠ 一行都没有
  const missing = buildNationTechSection(null)
  assert.equal(missing.rows.length, 0)
  assert.match(String(missing.emptyText), /没读到/)
  assert.notEqual(missing.emptyText, buildNationTechSection(techResp({ techs: [] })).emptyText)
})

test('拦截原因六种都要有话，且 NONE 不是拒绝', () => {
  assert.equal(nationTechBlockText('NONE'), null)
  assert.equal(nationTechBlockText('NATION_LOW'), '国家等级不够')
  assert.equal(nationTechBlockText('TREASURY_LOW'), '国库余额不足')
  assert.equal(nationTechBlockText('OFFICER_LIMIT'), '本周国库支出额度已经用完，等下周')
  assert.equal(nationTechBlockText('MAX_LEVEL'), '已经满级')
  assert.equal(nationTechBlockText('NOT_OFFICER'), '你没有研究国家科技的权限')
  // 将来多一种：给一句通用话，不许回退成枚举原文
  const unknown = nationTechBlockText('BRAND_NEW_REASON')
  assert.ok(unknown !== null && !unknown.includes('BRAND_NEW'))
})

test('外交：四个关系都有中文名，四个可选项与协议枚举逐一对应', () => {
  assert.equal(diplomacyLabel('ALLIED'), '盟约')
  assert.equal(diplomacyLabel('HOSTILE'), '敌对')
  assert.equal(diplomacyLabel('NEUTRAL'), '中立')
  assert.equal(diplomacyLabel('TRIBUTARY'), '附庸')
  assert.equal(diplomacyLabel('WHATEVER'), '未知关系')
  assert.deepEqual(DIPLOMACY_OPTIONS.map(o => o.key), ['ALLIED', 'TRIBUTARY', 'NEUTRAL', 'HOSTILE'])
  for (const option of DIPLOMACY_OPTIONS) {
    assert.equal(option.label, diplomacyLabel(option.key), `${option.key} 的按钮字与关系名必须一致`)
    assert.ok(option.note.length > 0, `${option.key} 要有一句说明：改一次就改变谁能打谁`)
  }
  // 变更之后那句话不许说"条约已生效"（C21：双方都记着才成立）
  const notice = diplomacyNotice('北伐营', 'ALLIED')
  assert.ok(notice.includes('北伐营') && notice.includes('盟约'))
  assert.ok(!notice.includes('已生效') && !notice.includes('条约生效'),
    '不许宣布条约成立：' + notice)
  assert.ok(notice.includes('对方也记着'), '必须把"要对方也记着"说清楚')
})

test('外交：还没打过一次交道是 null，不是"没有关系"', () => {
  assert.deepEqual(buildDiplomacyRows(null), [])
  const rows = buildDiplomacyRows([relation(), relation({ nationId: 'nation_y', nationName: '青龙盟', relation: 'ALLIED' })])
  assert.equal(rows.length, 2)
  assert.equal(rows[0]?.name, '北伐营')
  assert.equal(rows[0]?.relationText, '敌对')
  const sections = buildNationSections(null, null, [{ nationId: 'nation_x', name: '北伐营' }], [])
  assert.ok(sections.diplomacy.emptyText, '空表必须有一句说明，不是一片空白')
  assert.ok(sections.diplomacy.emptyText?.includes('还没有打过一次交道'))
})

test('任命：只给四个有任命入口的官职，国王与议员不摆出来', () => {
  assert.deepEqual(APPOINTABLE_OFFICES.map(o => o.key),
    ['PRIME_MINISTER', 'GENERAL', 'MINISTER', 'DIPLOMAT'])
  for (const office of APPOINTABLE_OFFICES) {
    assert.equal(office.label, OFFICE_LABELS[office.key], `${office.key} 的中文名要与人读的那份一致`)
  }
  // 摆出国王/议员就是 UI 在骗玩家（服务端当场拒绝），所以这两席一个都不许在列表里
  assert.ok(!APPOINTABLE_OFFICES.some(o => o.key === 'KING'))
  assert.ok(!APPOINTABLE_OFFICES.some(o => o.key === 'REPRESENTATIVE'))
  // 与协议枚举的差集恰好是这两席：多漏一个就会发现
  const missing = Object.keys(OFFICE_LABELS).filter(key => !APPOINTABLE_OFFICES.some(o => o.key === key))
  assert.deepEqual(missing.sort(), ['KING', 'REPRESENTATIVE'])
})

test('任命：候选是本盟成员，且要报出截掉了几个', () => {
  const members = Array.from({ length: 12 }, (_v, i) => ({ id: `p${i}`, name: `成员${i}` }))
  const rows = buildAppointRows(members)
  assert.equal(rows.length, 8)
  assert.equal(rows[0]?.name, '成员0')
  const sections = buildNationSections(null, null, [], members)
  assert.equal(sections.appoint.notice, null)
  const none = buildNationSections(null, null, [], [])
  assert.equal(none.appoint.rows.length, 0)
  assert.ok(none.appoint.notice, '没有成员时必须说清楚为什么没有，不是空白')
  // id 只用于发请求，永不上屏
  assert.ok(!JSON.stringify(none).includes('"p0"'))
})

test('科技那块的表头要说清国家等级与国库（服务端给的，不重算）', () => {
  const sections = buildNationSections(techResp({ nationLevel: 5, treasury: 7 }), null, [], [])
  assert.match(String(sections.tech.headerText), /国家等级 Lv5 · 国库 7/)
})

test('notice 的语气：成功与失败必须分开（红字显示"研究完成"是误读）', () => {
  const warn = buildNationPanel(input({ notice: '国库余额不足' }))
  assert.equal(warn.noticeTone, 'warn', '不给语气时按失败算（编排层每次成功都要显式翻成 ok）')
  const ok = buildNationPanel(input({ notice: '研究完成：林地开发 到 Lv2', noticeTone: 'ok' }))
  assert.equal(ok.noticeTone, 'ok')
  // 两态都要能把那句话原样带上去（不是靠颜色单独承载信息）
  assert.equal(ok.notice, '研究完成：林地开发 到 Lv2')
  assert.equal(warn.notice, '国库余额不足')
  // 没有 notice 时语气无所谓，但不许是 undefined（表现层直接拿它选色）
  assert.equal(buildNationPanel(input()).noticeTone, 'warn')
})

test('裸 token 的对手方：周税入账不许显示成「其他用途」（真链路回读屏才发现的那一处）', () => {
  // 三种形态各判一次：前缀式两种 + 裸 token 一种
  assert.equal(payeeLabel('player:player_x', NAMES), '孙俸禄')
  assert.equal(payeeLabel('sink:NATIONAL_TECH', NAMES), '国家科技')
  assert.equal(payeeLabel('weekly_tax', NAMES), '成员联盟周税')
  assert.equal(payeeLabel('disband_writeoff', NAMES), '亡国核销')
  assert.equal(payeeLabel('war_loot', NAMES), '国战战利品')
  // 查不到的裸 token 给「其他」，而且**不能是「其他用途」** —— 那读起来像支出
  assert.equal(payeeLabel('brand_new_income', NAMES), UNKNOWN_PAYEE)
  assert.notEqual(UNKNOWN_PAYEE, UNKNOWN_SINK)
  assert.ok(!UNKNOWN_PAYEE.includes('用途'))
  // 词表本身也要与 token 一一对上：加一个 token 却忘了加词，屏上就会回退成「其他」
  assert.deepEqual(Object.keys(PLAIN_PAYEE_LABELS).sort(), ['disband_writeoff', 'war_loot', 'weekly_tax'])
  // 屏上不许出现裸 token
  const row = buildTreasuryRow(
    { at: NOW, operatorId: 'system', counterparty: 'weekly_tax', amount: 10_000, reason: '国库周税', balanceAfter: 10_000 },
    NAMES, NOW, 0)
  assert.equal(row.headText, '成员联盟周税 · 10,000')
  assert.equal(row.detailText, '刚刚 · 系统 · 国库周税')
  assert.ok(!row.headText.includes('weekly_tax') && !row.headText.includes('其他用途'))
})
