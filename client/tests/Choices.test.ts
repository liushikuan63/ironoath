import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildBuildChoices, buildLineupChoices, buildSpeedupChoices,
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
