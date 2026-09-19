/**
 * 职责：编队编辑纯逻辑用例（编队保存 S1）。
 * 依赖：node:test + node:fs + game/hero/LineupEdit（不碰 cc）。
 *
 * <p><b>这一格最该钉住的一条**主张**</b>：服务端 `HeroRoster#setLineup` 只判"同一队内不许重复"，
 * **不判跨队重复** ⇒ 已在别的队的那名武将必须照常能点，只加一行标注。
 * 客户端把它灰掉的后果不是报错，是玩家明明能保存却被界面挡住、而且界面说不出为什么。
 * 所以既有用例钉"同队重复要灰"，也有用例钉"跨队不许灰"。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  LINEUP_SLOTS, buildLineupEdit, canSave, lineupBody, pickRows, saveText, slotLabel, slotRows,
} from '../assets/scripts/game/hero/LineupEdit'
import type { HeroView, LineupView } from '../assets/scripts/net/generated/HeroProtocol'

function hero(heroId: string, name: string, power = 1000): HeroView {
  return {
    heroId, name, rarity: 'SR', level: 30, exp: 0, expToNext: 500, maxLevel: 60,
    star: 1, maxStar: 5, awaken: 0, maxAwaken: 2,
    mainSkillId: 'skill_a', mainSkillName: '破阵', mainSkillLevel: 1,
    subSkillId: 'skill_b', subSkillName: '蓄势', subSkillLevel: 1, maxSkillLevel: 10,
    equips: [], baseAttrs: { might: 80, command: 70, wisdom: 60 },
    finalAttrs: { might: 80, command: 70, wisdom: 60 }, power, bondWith: null,
  }
}

function lineup(presetIndex: number, main: string | null, sub1: string | null,
  sub2: string | null): LineupView {
  return {
    presetIndex, main, sub1, sub2,
    bonus: { atkFixed: 0, defFixed: 0, skillFixed: 0, commandValue: 0, capped: false, breakdown: [] },
    activeBonds: [],
  }
}

const ROSTER = [hero('hero_a', '程远'), hero('hero_b', '沈牧'), hero('hero_c', '李劲')]

// ---------- 槽位行 ----------

test('三槽按 main/sub1/sub2 落位，空位写 empty 而不是印占位文字', () => {
  const rows = slotRows(lineup(0, 'hero_a', null, 'hero_c'), ROSTER, 'sub1')
  assert.deepEqual(rows.map((r) => [r.slot, r.heroName, r.empty]),
    [['main', '程远', false], ['sub1', null, true], ['sub2', '李劲', false]])
  assert.equal(rows[1]?.picking, true, '正在选的那一槽要标出来')
  assert.deepEqual(LINEUP_SLOTS.map(slotLabel), ['主将', '副将一', '副将二'])
})

test('槽位里那个 id 在名册读不到时按空位画 —— 宁可少画一个，也不把内部编号印给玩家', () => {
  const rows = slotRows(lineup(0, 'hero_gone', null, null), ROSTER, null)
  assert.equal(rows[0]?.empty, true)
  assert.equal(rows[0]?.heroName, null)
  assert.equal(rows[0]?.heroId, 'hero_gone', 'id 仍要留着（发请求要用），只是不上屏')
})

test('读不到那一套编队（老服务端）时三槽都是空的，不崩', () => {
  const rows = slotRows(null, ROSTER, null)
  assert.equal(rows.length, 3)
  assert.ok(rows.every((r) => r.empty))
})

// ---------- 选将弹层 ----------

test('同一队其他槽位占用的人要灰，并写明「已在本队其他槽位」', () => {
  const rows = pickRows(ROSTER, [lineup(0, 'hero_a', null, null)], 0, ['hero_a', null, null])
  assert.deepEqual(rows.map((r) => [r.heroId, r.usable]),
    [['hero_b', true], ['hero_c', true], ['hero_a', false]],
    '能点的排前面：一屏画不下时，被截断的只能是灰行')
  assert.equal(rows[2]?.reason, '已在本队其他槽位')
})

test('已在别的队的人**不许灰**，只加一行标注（服务端不判跨队重复）', () => {
  const rows = pickRows(ROSTER, [lineup(1, 'hero_b', null, null)], 0, [null, null, null])
  const b = rows.find((r) => r.heroId === 'hero_b')
  assert.equal(b?.usable, true, '灰掉就是客户端凭空加了一条服务端没有的规则')
  assert.equal(b?.reason, null)
  assert.equal(b?.note, '已在第 2 队', 'presetIndex 0 起算，画给玩家要 +1')
})

test('正在编辑的那一队自己不算「已在第 N 队」，多队同时在场就全列出来', () => {
  const lineups = [lineup(0, 'hero_a', null, null), lineup(1, null, 'hero_a', null),
    lineup(2, null, null, 'hero_a')]
  const editing = pickRows(ROSTER, lineups, 0, [null, 'hero_a', null])
  const a = editing.find((r) => r.heroId === 'hero_a')
  assert.equal(a?.note, '已在第 2、3 队')
  assert.equal(a?.usable, false, '第 1 队自己那一格占着它 ⇒ 这一队内不许重复')
  assert.equal(a?.reason, '已在本队其他槽位')
})

test('战力那一行照搬 HeroView.power，客户端不做任何换算', () => {
  const rows = pickRows([hero('hero_x', '贺兰迟', 12345)], [], 0, [null, null, null])
  assert.equal(rows[0]?.powerText, '战力 12345')
})

// ---------- 请求体与能不能保存 ----------

test('请求体：三槽原样落 main/sub1/sub2，空位是 null 而不是空串', () => {
  assert.deepEqual(lineupBody(2, { main: 'hero_a', sub1: null, sub2: 'hero_c' }),
    { presetIndex: 2, main: 'hero_a', sub1: null, sub2: 'hero_c' })
})

test('三槽全空是合法意图（清空这一队），不给发的是"名册还没读到"', () => {
  const empty = { main: null, sub1: null, sub2: null }
  assert.equal(canSave(empty, ROSTER), true)
  assert.equal(saveText(empty, ROSTER), '保存（清空这一队）')
  assert.equal(canSave(empty, []), false, '一个武将都没读到就发，等于把玩家送去未知武将的拒绝里')
  assert.equal(saveText(empty, []), '武将列表还没读到')
})

test('选了不在名册里的人不给发，键上写原因', () => {
  const slots = { main: 'hero_gone', sub1: null, sub2: null }
  assert.equal(canSave(slots, ROSTER), false)
  assert.equal(saveText(slots, ROSTER), '选中的武将不在名册里')
  assert.equal(saveText({ main: 'hero_a', sub1: 'hero_b', sub2: null }, ROSTER), '保存 · 2 名')
})

// ---------- 整块视图 ----------

test('整块视图：槽位画的是**编辑中**的状态而不是存档，队号画给玩家要 +1', () => {
  const view = buildLineupEdit(ROSTER, [lineup(0, 'hero_a', null, null)], 0,
    { main: 'hero_b', sub1: null, sub2: 'hero_c' }, null)
  assert.equal(view.presetText, '编队 1')
  assert.deepEqual(view.slots.map((r) => [r.slot, r.heroName, r.empty]),
    [['main', '沈牧', false], ['sub1', null, true], ['sub2', '李劲', false]],
    '存档里主将是程远，但刚换成沈牧 —— 立刻看得见，否则玩家以为没点上')
  assert.equal(view.pickingSlot, null)
  assert.deepEqual(view.picks, [], '没在选任何一槽就不该画一长串名单')
  assert.equal(view.canSave, true)
  assert.equal(view.saveText, '保存 · 2 名')
})

test('正在选副将一时：列表里"已在本队其他槽位"的只有主将与另一副将占的人', () => {
  const view = buildLineupEdit(ROSTER, [lineup(0, 'hero_a', null, null)], 0,
    { main: 'hero_a', sub1: null, sub2: 'hero_c' }, 'sub1')
  assert.deepEqual(view.picks.map((r) => [r.heroId, r.usable]),
    [['hero_b', true], ['hero_a', false], ['hero_c', false]])
  assert.equal(view.picks[1]?.reason, '已在本队其他槽位')
  assert.equal(view.picks[0]?.usable, true, '没被占的那个排第一')
  assert.equal(view.slots[1]?.picking, true, '正在选那一槽要标出来')
})

test('名册还没读到时：视图不给发，键上说实话', () => {
  const view = buildLineupEdit([], [], 2, { main: null, sub1: null, sub2: null }, null)
  assert.equal(view.presetText, '编队 3')
  assert.equal(view.canSave, false)
  assert.equal(view.saveText, '武将列表还没读到')
  assert.ok(view.slots.every((r) => r.empty), '读不到编队时三槽都是空的，不崩')
})

// ---------- 镜像保险：客户端只判服务端会判的那几条 ----------

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'server', 'game-core'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 server/game-core')
}

const ROSTER_SRC = 'server/game-core/src/main/java/com/ironoath/core/hero/HeroRoster.java'
const OWN_LOGIC = 'client/assets/scripts/game/hero/LineupEdit.ts'

test('服务端 setLineup 那三句还在原地：判同队重复、判已拥有、不判跨队', () => {
  const src = fs.readFileSync(path.join(repoRoot(), ROSTER_SRC), 'utf8')
  const body = src.slice(src.indexOf('public void setLineup'), src.indexOf('public void setLineup') + 2200)
  assert.ok(/同一支队伍里出现了两次/.test(body),
    '服务端那句同队重复的判定改了 ⇒ 上面那条"哪些人要灰"要重新对')
  assert.ok(/requireOwned\(main\)/.test(body) && /requireOwned\(sub\)/.test(body),
    '已拥有这条判定还在（客户端 canSave 钉的就是它）')
  assert.ok(!/其他预设|otherPreset|acrossLineup/.test(body),
    '服务端如果开始判跨队重复，客户端那条"跨队不许灰"就要反过来 —— 先在这里报警')
})

test('本纯逻辑唯一的 import 是协议类型（不抄表、不碰 cc）', () => {
  const src = fs.readFileSync(path.join(repoRoot(), OWN_LOGIC), 'utf8')
  const imports = (src.match(/^import[^\n]*$/gm) ?? [])
  assert.equal(imports.length, 1, `多出来的 import 就是第二份真相：${imports.join(' | ')}`)
  assert.ok(imports[0].includes('net/generated/HeroProtocol'))
})
