/**
 * 职责：HeroPanel 的单测 —— B06 §2（五条养成线）、§4（编队）、硬约束 1（每个加成标明乘区）。
 * 依赖：node:test / node:assert。
 *
 * <p>重点盯三条：
 * <ol>
 *   <li><b>乘区必须标出来</b>（硬约束 1）。不标乘区，玩家侧的「为什么我这么强」
 *       与开发侧的「数值为什么算错」都无从查起 —— 这条约束存在的理由就是可解释性</li>
 *   <li><b>触到乘区上限必须提示</b>。不说的话玩家会把资源一直投进去而看不到变化，
 *       那会被理解成数值造假</li>
 *   <li><b>客户端不算任何数值</b>。属性、加成、战力全部照服务端下发的原样搬运，
 *       本模块连除法都不做（定点格式化走 core/FixedPoint）</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { buildHeroPanel, buildHeroRow, buildLineupPanel, rarityOrder, starText, zoneText } from '../assets/scripts/game/hero/HeroPanel'
import type { HeroListResp, HeroView, LineupView } from '../assets/scripts/net/generated/HeroProtocol'

const NAMES = new Map<string, string>([['h1', '裴惊澜'], ['h2', '沈砚'], ['h3', '李承鄞']])

function hero(overrides: Partial<HeroView> = {}): HeroView {
  return {
    heroId: 'h1',
    name: '裴惊澜',
    rarity: 'SSR',
    level: 60,
    exp: 120,
    expToNext: 800,
    maxLevel: 60,
    star: 4,
    maxStar: 5,
    awaken: 1,
    maxAwaken: 3,
    mainSkillId: 'skill_break_cavalry',
    mainSkillName: '破骑',
    mainSkillLevel: 3,
    subSkillId: 'skill_iron_wall',
    subSkillName: '铁壁',
    subSkillLevel: 1,
    maxSkillLevel: 5,
    equips: [
      { slot: 'WEAPON', uid: 'eq-7f3a', equipId: 'equip_weapon_01', name: '铁剑', forgeLevel: 2 },
      { slot: 'MOUNT', uid: 'eq-8b21', equipId: 'equip_mount_01', name: '照夜玉狮子', forgeLevel: 0 },
    ],
    baseAttrs: { might: 100, command: 80, wisdom: 60 },
    finalAttrs: { might: 162, command: 120, wisdom: 78 },
    power: 4200,
    bondWith: 'h2',
    ...overrides,
  }
}

function bonus(overrides: Partial<LineupView['bonus']> = {}): LineupView['bonus'] {
  return {
    atkFixed: 6200,
    defFixed: 4100,
    skillFixed: 1500,
    commandValue: 240,
    capped: false,
    breakdown: [
      { source: '裴惊澜 Lv60 ★4（主将）', value: 6200, zone: 'HERO' },
      { source: '与沈砚同队', value: 1000, zone: 'BOND' },
      { source: '铁骑套装 4 件', value: 2500, zone: 'EQUIP_SET' },
    ],
    ...overrides,
  }
}

function lineup(overrides: Partial<LineupView> = {}): LineupView {
  return {
    presetIndex: 0,
    main: 'h1',
    sub1: 'h2',
    sub2: null,
    bonus: bonus(),
    activeBonds: ['h2'],
    ...overrides,
  }
}

function heroResp(heroes: HeroView[], lineups: LineupView[] = []): HeroListResp {
  return {
    heroes, lineups, troopCap: 2400, troopsInUse: 800, serverNow: 0,
    fragments: [{
      itemId: 'item_mat_hero_frag_h1', name: '胡车儿碎片', count: 12,
      // 门槛与候选随行下发（V03-d 第六条线）：本用例只验钱包那一行，候选给一档就够
      composeFragment: 50, candidates: [{ heroId: 'hero_h2', name: '沈砚' }],
    }],
  }
}

// ---------- 五条养成线 ----------

test('武将行把五条养成线（等级/星级/觉醒/技能/装备）与属性、战力都搬出来', () => {
  const row = buildHeroRow(hero(), NAMES)
  assert.equal(row.name, '裴惊澜')
  assert.equal(row.rarity, 'SSR')
  assert.equal(row.levelText, 'Lv60/60')
  assert.equal(row.starText, '★★★★☆')
  assert.equal(row.awakenText, '觉醒 1/3')
  assert.equal(row.mainSkillText, '破骑 Lv3/5',
    '名字取自服务端下发的 mainSkillName —— 这一条原先钉的是 skill_break_cavalry（把配置 id 印给玩家）')
  assert.equal(row.powerText, '战力 4200')
  // 基础与养成后都显示，玩家才看得出养成到底涨了多少
  assert.equal(row.attrText, '武力 100→162 统率 80→120 智力 60→78')
})

test('技能那一行不出现配置行 id（#255 建筑名、#268 资源名之后的第三处外泄）', () => {
  const row = buildHeroRow(hero(), NAMES)
  const onScreen = `${row.mainSkillText}｜${row.subSkillText}`
  for (const leaked of ['skill_break_cavalry', 'skill_iron_wall']) {
    assert.equal(onScreen.includes(leaked), false, `技能行把 ${leaked} 直接印给了玩家`)
  }
  assert.equal(onScreen.includes('破骑') && onScreen.includes('铁壁'), true,
    '两条都要有名字，缺一条就是"只改了主技能"')
})

test('副技能文案必须写明「仅副将位生效」（B06 §2.4）：不写玩家会把主将放副将位，然后以为技能坏了', () => {
  assert.match(buildHeroRow(hero(), NAMES).subSkillText, /（仅副将位生效）$/)
})

test('满级时不再显示经验条（expToNext 为 0，显示「经验 0/0」是噪音）', () => {
  assert.equal(buildHeroRow(hero({ level: 60, maxLevel: 60 }), NAMES).expText, null)
  assert.equal(buildHeroRow(hero({ level: 12, maxLevel: 60 }), NAMES).expText, '经验 120/800')
})

test('装备四槽按 WEAPON/ARMOR/MOUNT/ACCESSORY 落位：空槽显示「空」，穿着的显示名字与强化等级', () => {
  const row = buildHeroRow(hero(), NAMES)
  assert.deepEqual(row.equipTexts, ['武器：铁剑 +2', '护甲：空', '坐骑：照夜玉狮子', '饰品：空'],
    '这一条原先钉的是 equip_weapon_01（把行 id / 实例 uid 印给玩家）；+0 不写加号')
  assert.equal(row.emptyEquipSlots, 2)
  const onScreen = row.equipTexts.join('｜')
  assert.equal(onScreen.includes('eq-') || onScreen.includes('equip_'), false,
    '画面上不许出现实例 uid 或 equip 表行 id（#255/#268/#278/#281 同族第五处）')

  // 新形状只列穿着的，所以"满"与"裸"分别是 4 项与 0 项
  const full = buildHeroRow(hero({
    equips: [
      { slot: 'WEAPON', uid: 'a', equipId: 'e1', name: '一', forgeLevel: 0 },
      { slot: 'ARMOR', uid: 'b', equipId: 'e2', name: '二', forgeLevel: 1 },
      { slot: 'MOUNT', uid: 'c', equipId: 'e3', name: '三', forgeLevel: 0 },
      { slot: 'ACCESSORY', uid: 'd', equipId: 'e4', name: '四', forgeLevel: 0 },
    ],
  }), NAMES)
  assert.equal(full.emptyEquipSlots, 0)
  assert.equal(full.equipTexts[1], '护甲：二 +1')
  assert.equal(buildHeroRow(hero({ equips: [] }), NAMES).emptyEquipSlots, 4)
})

test('缘分对象显示名字而不是 id；查不到名字时原样显示 id（静默变空白会让玩家以为没缘分）', () => {
  assert.equal(buildHeroRow(hero({ bondWith: 'h2' }), NAMES).bondText, '缘分：沈砚')
  assert.equal(buildHeroRow(hero({ bondWith: 'unknown_hero' }), NAMES).bondText, '缘分：unknown_hero')
  assert.equal(buildHeroRow(hero({ bondWith: null }), NAMES).bondText, null)
})

test('星级文本用实心/空心星；负数或非整数立刻抛错', () => {
  assert.equal(starText(0, 5), '☆☆☆☆☆')
  assert.equal(starText(3, 5), '★★★☆☆')
  assert.equal(starText(5, 5), '★★★★★')
  assert.equal(starText(7, 5), '★★★★★', '超过上限就截到上限，绝不画出 7 颗星')
  assert.throws(() => starText(-1, 5), /非负整数/)
  assert.throws(() => starText(1.5, 5), /非负整数/)
})

// ---------- 编队与乘区（硬约束 1） ----------

test('每一行加成都带乘区标签；不认识的乘区原样显示而不是丢掉', () => {
  const panel = buildLineupPanel(lineup(), NAMES)
  assert.deepEqual(panel.breakdownLines, [
    '[武将] 裴惊澜 Lv60 ★4（主将） +62%',
    '[缘分] 与沈砚同队 +10%',
    '[装备套装] 铁骑套装 4 件 +25%',
  ])
  assert.equal(zoneText('HERO'), '[武将]')
  assert.equal(zoneText('BOND'), '[缘分]')
  assert.equal(zoneText('EQUIP_SET'), '[装备套装]')
  // 静默丢掉一行加成，玩家算出来的总数就会和面板对不上，而那会被当成数值造假
  assert.equal(zoneText('SOMETHING_NEW'), '[SOMETHING_NEW]')
})

test('只有两个武将侧乘区（攻击/防御）加技能强度，没有独立的生命乘区 —— B05 把生命折进了有效防御', () => {
  const panel = buildLineupPanel(lineup(), NAMES)
  assert.equal(panel.atkText, '攻击 +62%')
  assert.equal(panel.defText, '防御 +41%')
  assert.equal(panel.skillText, '技能强度 +15%')
  assert.equal(panel.commandText, '统帅 240')
})

test('触到乘区上限时必须提示：继续堆养成不会再变强，不说玩家会以为数值造假', () => {
  assert.equal(buildLineupPanel(lineup({ bonus: bonus({ capped: true }) }), NAMES).cappedHint,
    '已触到乘区上限：继续堆养成不会再变强，资源请转投其它系统')
  assert.equal(buildLineupPanel(lineup(), NAMES).cappedHint, null)

  const panel = buildHeroPanel(heroResp([hero()], [lineup({ bonus: bonus({ capped: true }) })]))
  assert.notEqual(panel.lineups.find((item) => item.capped), undefined)
})

test('编队空位显示「空」并计数：三个位置少一个，玩家要一眼看出该补谁', () => {
  const panel = buildLineupPanel(lineup(), NAMES)
  assert.deepEqual(panel.slotTexts, ['主将：裴惊澜', '副将：沈砚', '副将：空'])
  assert.equal(panel.emptySlots, 1)
  assert.equal(panel.presetText, '编队 1')

  const empty = buildLineupPanel(lineup({ main: null, sub1: null, sub2: null }), NAMES)
  assert.equal(empty.emptySlots, 3)
})

test('已激活的缘分把 id 换成名字（成对同队才算，这个判定在服务端）', () => {
  assert.deepEqual(buildLineupPanel(lineup({ activeBonds: ['h2', 'h3'] }), NAMES).bondTexts, ['沈砚', '李承鄞'])
  assert.deepEqual(buildLineupPanel(lineup({ activeBonds: [] }), NAMES).bondTexts, [])
})

test('槽位上的 heroId 在名册里查不到名字时写「未知武将」，绝不把内部编号印上队伍栏', () => {
  // 这条会失败：把 `?? '未知武将'` 改回 `?? heroId` 就红。
  // 形态与 `LineupEdit` 那条同源（#255 家族）：玩家读到的应该是一句人话，不是 `hero_guanyu`。
  const stale = buildLineupPanel(lineup({ main: 'hero_gone', sub1: 'h2', sub2: null }), NAMES)
  assert.deepEqual(stale.slotTexts, ['主将：未知武将', '副将：沈砚', '副将：空'])
  assert.ok(!stale.slotTexts.some((text) => text.includes('hero_gone')),
    `队伍栏里出现了内部编号：${stale.slotTexts.join(' / ')}`)
})

// ---------- 面板汇总 ----------

test('面板汇总：武将数、带兵上限、碎片持有量都照服务端原样搬', () => {
  const panel = buildHeroPanel(heroResp([hero(), hero({ heroId: 'h2', name: '沈砚', rarity: 'SR' })], [lineup()]))
  assert.equal(panel.heroes.length, 2)
  assert.equal(panel.lineups.length, 1)
  assert.equal(panel.troopCapText, '带兵上限 2400（已用 800）')
  assert.deepEqual(panel.fragmentTexts, ['胡车儿碎片 ×12'],
    '碎片行印的是服务端下发的名字 —— 这一条原先钉的是 item_mat_hero_frag_h1（把行 id 印给玩家）')
  assert.equal(panel.fragmentTexts.join('｜').includes('item_mat_'), false,
    '画面上不许出现 item 表的行 id（#255/#268/#278 同族第四处）')
})

test('一个武将都没有时也能组装（刚建号还没抽到）', () => {
  const panel = buildHeroPanel(heroResp([]))
  assert.deepEqual(panel.heroes, [])
  assert.deepEqual(panel.lineups, [])
  assert.equal(panel.troopCapText, '带兵上限 2400（已用 800）')
})

test('稀有度页签顺序固定为 SSR→N，但武将列表本身照搬服务端顺序', () => {
  assert.deepEqual(rarityOrder(), ['SSR', 'SR', 'R', 'N'])
  const panel = buildHeroPanel(heroResp([
    hero({ heroId: 'a', rarity: 'N' }),
    hero({ heroId: 'b', rarity: 'SSR' }),
    hero({ heroId: 'c', rarity: 'R' }),
  ]))
  assert.deepEqual(panel.heroes.map((item) => item.heroId), ['a', 'b', 'c'],
    '客户端不重排：排序规则属于服务端，双端各排一次就会出现不一致')
})
