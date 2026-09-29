import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildArmyQueueChoices, buildBuildChoices, buildChestOpenChoices, buildLineupChoices,
  buildResearchSpeedupChoices,
  buildSpeedupChoices, buildTrainSpeedupChoices,
} from '../assets/scripts/game/session/Choices'
import type { ArmyListResp } from '../assets/scripts/net/generated/ArmyProtocol'
import type { CityListResp } from '../assets/scripts/net/generated/CityProtocol'
import type { HeroListResp } from '../assets/scripts/net/generated/HeroProtocol'

test('建造候选的详情走中文类型名，不许把配置枚举原文印给玩家', () => {
  const city = {
    buildOptions: [
      { configId: 'lumber_camp', name: '伐木场', type: 'RESOURCE', requireMainLevel: 1, requireBuilding: null },
      { configId: 'barracks', name: '兵营', type: 'MILITARY', requireMainLevel: 2, requireBuilding: null },
    ],
  } as unknown as CityListResp

  const choices = buildBuildChoices(city)
  assert.equal(choices[0]?.label, '伐木场')
  assert.equal(choices[0]?.detail, '资源 · 需要主城 1 级')
  assert.equal(choices[1]?.detail, '军事 · 需要主城 2 级')
  // 这条判据要能失败：详情以全大写 ASCII 开头就说明又走了枚举原文
  // （现场症状正是选择器上印出「RESOURCE · 需要主城 1 级」）
  for (const choice of choices) {
    assert.ok(!/^[A-Z][A-Z_]*\b/.test(choice.detail),
      `详情以配置枚举开头：${choice.detail}`)
  }
})

test('加速道具目标只列正在升级或训练的队列，已完成的不能选', () => {
  const city = {
    buildings: [
      { id: 'b1', configId: 'main_city', name: '主城', level: 2, status: 'UPGRADING', remainingSeconds: 125 },
      { id: 'b2', configId: 'farm', name: '农田', level: 1, status: 'IDLE', remainingSeconds: null },
    ],
  } as unknown as CityListResp
  const army = {
    units: [
      { unitId: 'unit_infantry_t1', name: '步兵', training: 10, remainingSeconds: 300 },
      { unitId: 'unit_archer_t1', name: '弓兵', training: 0, remainingSeconds: null },
    ],
  } as unknown as ArmyListResp

  const choices = buildSpeedupChoices(city, army)
  assert.deepEqual(choices.map((choice) => choice.targetId), ['b1', 'unit_infantry_t1'])
  assert.match(choices[0]?.detail ?? '', /2分5秒/)
  assert.match(choices[1]?.detail ?? '', /5分0秒/)
  // 标签是玩家看得见的：用配置 id 会印成 "main_city Lv2"，缺 name 会印成 "undefined Lv2"
  assert.equal(choices[0]?.label, '主城 Lv2')
})

test('出战阵容只列已编成的主将队，携带当前全部可用兵力', () => {
  const heroes = {
    heroes: [
      { heroId: 'h1', name: '卫无咎' },
      { heroId: 'h2', name: '沈砚秋' },
    ],
    lineups: [
      { presetIndex: 0, main: 'h1', sub1: 'h2', sub2: null, bonus: { commandValue: 120 } },
      { presetIndex: 1, main: null, sub1: 'h2', sub2: null, bonus: { commandValue: 0 } },
    ],
  } as unknown as HeroListResp
  const army = {
    units: [
      { unitId: 'unit_infantry_t1', name: '步兵', count: 100 },
      { unitId: 'unit_archer_t1', name: '弓兵', count: 25 },
    ],
  } as unknown as ArmyListResp

  const choices = buildLineupChoices(heroes, army)
  assert.equal(choices.length, 1)
  assert.equal(choices[0]?.label, '编队 1 · 卫无咎')
  assert.deepEqual(choices[0]?.heroes, ['h1', 'h2'])
  assert.deepEqual(choices[0]?.units, [
    { unitId: 'unit_infantry_t1', count: 100 },
    { unitId: 'unit_archer_t1', count: 25 },
  ])
})

test('首次建造候选保留配置门槛，未放置建筑按服务端顺序展示', () => {
  const city = {
    buildOptions: [
      {
        configId: 'lumber_camp', name: '伐木场', type: 'RESOURCE',
        requireMainLevel: 1, requireBuilding: null, requireBuildingName: null,
      },
      {
        configId: 'academy', name: '学院', type: 'SCIENCE',
        requireMainLevel: 3, requireBuilding: 'main_city', requireBuildingName: '主城',
      },
    ],
  } as unknown as CityListResp

  const choices = buildBuildChoices(city)
  assert.deepEqual(choices.map((choice) => choice.id), ['lumber_camp', 'academy'])
  assert.match(choices[1]?.detail ?? '', /需要主城 3 级/)
  // 前置建筑显示的是**服务端下发的显示名**，不是 building.json 的行 id。
  // 这条断言原先写的是 /前置 main_city/ —— 那正是把内部编号印给玩家的形态（#255 同族，
  // 2026-09-21 复检在 buildOptionDetail 上抓到），现在钉它的反面。
  assert.match(choices[1]?.detail ?? '', /前置 主城/)
  assert.ok(!/main_city/.test(choices[1]?.detail ?? ''),
    `详情里出现了配置行 id：${choices[1]?.detail}`)
})

test('前置建筑的名字缺失时退成「前置建筑」这句人话，绝不退回印 id', () => {
  const city = {
    buildOptions: [
      {
        configId: 'academy', name: '学院', type: 'SCIENCE',
        requireMainLevel: 3, requireBuilding: 'main_city',
        // 老服务端不下发这一位（滚动升级期）
        requireBuildingName: null,
      },
    ],
  } as unknown as CityListResp

  const detail = buildBuildChoices(city)[0]?.detail ?? ''
  assert.match(detail, /前置建筑/, '缺名字时要给一句人话')
  assert.ok(!/main_city/.test(detail), `缺名字时把 id 印了出来：${detail}`)
})

// ---------- 军队「队列」菜单（B26 S15）----------

const queueArmy = {
  units: [
    { unitId: 'unit_infantry_t1', name: '重步', training: 30, remainingSeconds: 600 },
    { unitId: 'unit_archer_t1', name: '长弓', training: 0, remainingSeconds: null },
  ],
} as unknown as ArmyListResp

test('队列菜单只在那一口真的在练时给出「取消」，练完或没练就是空的', () => {
  assert.deepEqual(buildArmyQueueChoices(queueArmy, 'unit_infantry_t1').map((it) => it.id),
    ['CANCEL_TRAIN'])
  assert.deepEqual(buildArmyQueueChoices(queueArmy, 'unit_archer_t1'), [],
    '没有可取消的东西就画一张空菜单，等于一颗点了没反应的键')
  assert.deepEqual(buildArmyQueueChoices(queueArmy, 'unit_ghost'), [], '军队里没这个兵种也不给菜单')
  assert.deepEqual(buildArmyQueueChoices(null, 'unit_infantry_t1'), [], '军队数据还没到不猜')
})

test('取消那条要说清退的是什么：数量与兵种名都取自服务端下发的行', () => {
  const detail = buildArmyQueueChoices(queueArmy, 'unit_infantry_t1')[0]?.detail ?? ''
  assert.match(detail, /30/, '把还在练的数量写出来，玩家才知道取消掉的是哪一口')
  assert.match(detail, /重步/)
  assert.match(detail, /按比例退回/, '退多少由服务端按配置算，这里只说口径不说数字')
})

test('开宝箱的档位只按"手里有几个"给，不抄逐箱上限', () => {
  assert.deepEqual(buildChestOpenChoices(0), [], '没有就不画菜单')
  assert.deepEqual(buildChestOpenChoices(3).map(o => o.id), ['1'],
    '手里 3 个就不该给"开 5 个"—— 那是一颗必然被服务端拒的选项')
  assert.deepEqual(buildChestOpenChoices(12).map(o => o.id), ['1', '5', '10', 'all'])
})

test('手里超过一次上限时，「全开」说的是上限而不是持有数', () => {
  const all = buildChestOpenChoices(250).find(o => o.id === 'all')
  assert.equal(all?.label, '全开 100 个')
  assert.match(all?.detail ?? '', /一次最多开 100 个/,
    '要说清为什么是 100 而不是 250，否则玩家以为另外 150 个被吞了')
  // 这句是给玩家看的，工程词不能上屏（第 12 道门盯这条；上一版这条断言把「协议」钉成了规格）
  assert.doesNotMatch(all?.detail ?? '', /协议|服务端|下发|幂等/)
})

test('研究加速只列 effectKind 对得上的那一种令，建造令与训练令不出现', () => {
  const bag = {
    items: [
      { itemId: 'i_research', name: '研究令', type: 'SPEEDUP', effectKind: 'REDUCE_RESEARCH_SECONDS', count: 3 },
      { itemId: 'i_build', name: '建造令', type: 'SPEEDUP', effectKind: 'REDUCE_BUILD_SECONDS', count: 9 },
      { itemId: 'i_train', name: '训练令', type: 'SPEEDUP', effectKind: 'REDUCE_TRAIN_SECONDS', count: 2 },
    ],
  } as never
  assert.deepEqual(buildResearchSpeedupChoices(bag).map((o) => `${o.itemId}:${o.count}`),
    ['i_research:1', 'i_research:3'],
    '走错一种会被服务端拒（它宁可响也不静默按另一种加速处理），所以干脆不列出来')
  assert.deepEqual(buildResearchSpeedupChoices(null), [], '背包没读到不猜')
})

test('只剩一张时不给「全用」那一档（两档内容一样，多一颗没意义的键）', () => {
  const only = buildResearchSpeedupChoices({
    items: [{
      itemId: 'i_research', name: '研究令', type: 'SPEEDUP',
      effectKind: 'REDUCE_RESEARCH_SECONDS', count: 1,
    }],
  } as never)
  assert.deepEqual(only.map((o) => o.count), [1])
})

test('候选里不许编"一张减多少秒"——BagItem 没下发 effectValue', () => {
  const one = buildResearchSpeedupChoices({
    items: [{
      itemId: 'i_research', name: '研究令', type: 'SPEEDUP',
      effectKind: 'REDUCE_RESEARCH_SECONDS', count: 3,
    }],
  } as never)[0]
  assert.equal(/减|分|秒/.test(one?.detail ?? ''), false, `detail 写的是「${one?.detail}」`)
  assert.match(one?.detail ?? '', /持有 3 张/)
})

test('治疗加速只列训练令 —— 与服务端 treatSpeedUp 的校验同源（V12）', () => {
  const bag = {
    items: [
      { itemId: 'i_train', name: '一小时训练令', type: 'SPEEDUP', effectKind: 'REDUCE_TRAIN_SECONDS', count: 4 },
      { itemId: 'i_build', name: '一小时建造令', type: 'SPEEDUP', effectKind: 'REDUCE_BUILD_SECONDS', count: 9 },
      { itemId: 'i_research', name: '一小时研究令', type: 'SPEEDUP', effectKind: 'REDUCE_RESEARCH_SECONDS', count: 2 },
    ],
  } as never
  assert.deepEqual(buildTrainSpeedupChoices(bag).map((o) => `${o.itemId}:${o.count}`),
    ['i_train:1', 'i_train:4'],
    '服务端要求 effectKind 正是 REDUCE_TRAIN_SECONDS，挑错了会被 ITEM_CANNOT_USE 拒 —— 所以不列出来')
  // 同一个参数化函数的两条路径各筛各的：研究那一半一字不变（改这一格不许动到它）
  assert.deepEqual(buildResearchSpeedupChoices(bag).map((o) => o.itemId),
    ['i_research', 'i_research'])
  assert.deepEqual(buildTrainSpeedupChoices(null), [], '背包没读到不猜')
})
