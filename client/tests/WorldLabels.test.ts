/**
 * 职责：钉住大地图实体格上那一行字的文案 —— 尤其是"资源格印的是中文名而不是枚举原文"。
 * 依赖：node:test / node:assert / node:fs；只读引擎无关的 `game/world/WorldLabels`。
 *
 * <p><b>为什么值得单独立一条（而不是继续靠跑真产物看截图）</b>：`entityCaption` 原本长在
 * `scene/WorldMap.ts` 里，于是这条判据只能"进浏览器 + 缩放到 1 + 数标签"，而 #269/#272 实测：
 * 默认缩放档根本不画资源格标签（`renderEntities` 只在 `zoom > 0` 时给文案），headless 又送不进触摸
 * —— 那道门永远走不到正向分支，只能退 2 说"请人工看图"。函数搬进无引擎依赖的模块后，
 * 同一件事变成一条**每次 `npm test` 都会跑**的判据：不需要浏览器、不需要缩放、不需要点得动任何东西。
 *
 * <p>每条都能失败：
 * ① 有人把 `resourceName()` 换回 `entity.resourceType` ⇒ 地图上重新冒出 `IRON`；
 * ② 契约新增第七种资源而中文名没跟 ⇒ 那种资源在地图上印原文；
 * ③ 有人把"没有文案"写成 `null` / `undefined` 而不是空串 ⇒ 格子上直接印出 `null`；
 * ④ 有人拿客户端自己判的等级/昵称替掉服务端下发的字段 ⇒ 与"只搬运不加工"的纪律冲突。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import type { WorldEntity, WorldEntityType } from '../assets/scripts/net/generated/WorldProtocol'
import {
  CAPTION_PLATE_HEIGHT, captionBox, captionPlateWidth, captionPriority, entityCaption,
  pickVisibleCaptions,
} from '../assets/scripts/game/world/WorldLabels'
import type { CaptionCandidate, CaptionViewport } from '../assets/scripts/game/world/WorldLabels'

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'resource.json'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/resource.json')
}

const rows = (JSON.parse(
  fs.readFileSync(path.join(repoRoot(), 'contract', 'config', 'resource.json'), 'utf8'),
).rows as { id: string, name: string }[])

/** 契约那截 `export type ResourceType = ...` 的字面量（与 ResourceNames.test 同一取法：真源是 schema）。 */
function protocolResourceTypes(): string[] {
  const src = fs.readFileSync(
    path.join(repoRoot(), 'client', 'assets', 'scripts', 'net', 'generated', 'Protocol.ts'), 'utf8')
  const block = /export type ResourceType =\r?\n([\s\S]*?)\r?\n\r?\n/.exec(src)
  const body = block?.[1] ?? ''
  assert.ok(body !== '', '在 Protocol.ts 里找不到 ResourceType 的定义块 —— 生成格式变了，本判据要跟着改')
  return [...body.matchAll(/'([A-Z_]+)'/g)]
    .map((m) => m[1] ?? '')
    .filter((t) => t !== '')
}

/** 服务端下发形状的夹具：除 type 外全部默认空，各用例只改自己要的那一列。 */
function entity(over: Partial<WorldEntity> & { type: WorldEntity['type'] }): WorldEntity {
  return {
    id: 'e1', x: 10, y: 20, level: null, ownerName: null, allianceTag: null,
    marchStatus: null, resourceType: null, load: null, ...over,
  }
}

test('资源格印的是配置表里的中文名，不是 WOOD/STONE/IRON/GRAIN（#268 的缺陷形态）', () => {
  for (const row of rows) {
    const caption = entityCaption(entity({ type: 'RESOURCE', resourceType: row.id }))
    assert.equal(caption, row.name, `资源 ${row.id} 的格子文案是「${caption}」，表里的中文名是「${row.name}」`)
  }
  assert.ok(rows.length >= 6, `resource.json 只认出 ${rows.length} 行，判据本身失效了`)
})

test('契约枚举里的每一种资源，地图标签都不出现枚举原文（新增第七种而中文名没跟时这条会红）', () => {
  const types = protocolResourceTypes()
  assert.ok(types.length >= 6, `只从 Protocol.ts 认出 ${types.length} 个 ResourceType 字面量，判据本身失效了`)
  const leaked = types.filter((t) => new RegExp(`(^|[^A-Z])${t}([^A-Z]|$)`).test(
    entityCaption(entity({ type: 'RESOURCE', resourceType: t })),
  ))
  assert.deepEqual(leaked, [], `这些资源类型的地图标签里仍印着枚举原文：${leaked.join('、')}`)
})

test('没有资源类型的资源格给空串，不给 null / undefined / 字面量 "null"', () => {
  const caption = entityCaption(entity({ type: 'RESOURCE', resourceType: null }))
  assert.equal(caption, '')
  assert.ok(!/^(null|undefined)$/i.test(caption), '空值被字符串化了，格子上会印出 null')
})

test('其余四种实体各写自己那一列，且都只搬运服务端已下发的字段', () => {
  assert.equal(entityCaption(entity({ type: 'CITY', ownerName: '无名君主', level: 3 })), '无名君主')
  // 城没有昵称时退回等级：这是"少一列"时的兜底，不是客户端自己判出来的数
  assert.equal(entityCaption(entity({ type: 'CITY', ownerName: null, level: 7 })), 'Lv7')
  assert.equal(entityCaption(entity({ type: 'MONSTER', level: 9 })), 'Lv9')
  assert.equal(entityCaption(entity({ type: 'MONSTER', level: null })), '')
  assert.equal(entityCaption(entity({ type: 'BUILDING', allianceTag: '铁誓', level: 2 })), '铁誓')
  assert.equal(entityCaption(entity({ type: 'BUILDING', allianceTag: null, level: 2 })), 'Lv2')
  assert.equal(entityCaption(entity({ type: 'MARCH', load: 1200 })), '1200')
  assert.equal(entityCaption(entity({ type: 'MARCH', load: null })), '')
  assert.equal(entityCaption(entity({ type: 'EMPTY' })), '')
})

test('任何一类实体的文案都不会是 undefined / null 的字面量形式', () => {
  const samples: WorldEntity['type'][] = ['CITY', 'MONSTER', 'RESOURCE', 'BUILDING', 'MARCH', 'EMPTY']
  for (const type of samples) {
    const caption = entityCaption(entity({ type }))
    assert.ok(!/^(null|undefined)$/i.test(caption), `${type} 在字段全空时印出了「${caption}」`)
  }
})

// ---------- 名牌藏牌口径（#275 实测：缩放 1 一格 30px、最窄的牌 40px，横向相邻两格放不下两张牌） ----------

/** 缩放 1 的真实尺度：一格 30px，牌高 18px。 */
const CELL = 30

function plate(key: string, type: WorldEntityType, centerX: number, caption: string, pinned = false): CaptionCandidate {
  return plateAt(key, type, centerX, 0, caption, pinned)
}

function plateAt(key: string, type: WorldEntityType, centerX: number, centerY: number,
                 caption: string, pinned = false): CaptionCandidate {
  return { key, type, box: captionBox(centerX, centerY, 15, caption), pinned }
}

function keptKeys(candidates: readonly CaptionCandidate[]): string[] {
  return Array.from(pickVisibleCaptions(candidates)).sort()
}

function keptKeysIn(candidates: readonly CaptionCandidate[], viewport: CaptionViewport): string[] {
  return Array.from(pickVisibleCaptions(candidates, viewport)).sort()
}

test('牌盒尺寸由文案算出：宽按字数、高就是画出来的那一条', () => {
  const box = captionBox(100, 0, 15, '石料')
  assert.equal(box.width, captionPlateWidth('石料'))
  assert.equal(box.height, CAPTION_PLATE_HEIGHT)
  assert.equal(box.x, 100 - box.width / 2, '盒子要以实体为心，否则判交判偏')
  assert.ok(CAPTION_PLATE_HEIGHT > 0 && CAPTION_PLATE_HEIGHT <= 24,
    `板高 ${CAPTION_PLATE_HEIGHT} 已经高过一格的三分之一，藏牌会藏得比看见的多`)
})

test('横向相邻两格的两张牌放不下：必藏其一（这条就是 #275 量到的那 4 对的形状）', () => {
  const kept = keptKeys([
    plate('RESOURCE:a', 'RESOURCE', 0, '石料'),
    plate('RESOURCE:b', 'RESOURCE', CELL, '木材'),
  ])
  assert.equal(kept.length, 1, `相邻两格留了 ${kept.length} 张牌，两盒原点只差 ${CELL}px`)
})

test('隔一格就能放下两张：藏牌不是"无脑藏一半"（阈值判错时这条会红）', () => {
  const kept = keptKeys([
    plate('RESOURCE:a', 'RESOURCE', 0, '石料'),
    plate('RESOURCE:b', 'RESOURCE', CELL * 2, '木材'),
  ])
  assert.deepEqual(kept, ['RESOURCE:a', 'RESOURCE:b'])
})

test('连排六格：留下的任意两张牌都不相交，且至少留下一张', () => {
  const row = Array.from({ length: 6 }, (_, i) =>
    plate(`RESOURCE:r${i}`, 'RESOURCE', i * CELL, '粮草'))
  const visible = pickVisibleCaptions(row)
  assert.ok(visible.size >= 1, '一整排牌全被藏了，那一屏等于没有标注')
  const boxes = row.filter((entry) => visible.has(entry.key)).map((entry) => entry.box)
  for (let i = 0; i < boxes.length; i += 1) {
    for (let j = i + 1; j < boxes.length; j += 1) {
      const a = boxes[i] as { x: number, y: number, width: number, height: number }
      const b = boxes[j] as { x: number, y: number, width: number, height: number }
      const ox = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x)
      const oy = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y)
      assert.ok(!(ox > 1 && oy > 1), `留下的两张牌仍相交 ${Math.round(ox)}×${Math.round(oy)} —— 藏牌没生效`)
    }
  }
})

test('挤的时候按 城 > 资源 > 联盟建筑 > 怪物 > 行军 留牌', () => {
  assert.ok(captionPriority('CITY') < captionPriority('RESOURCE'))
  assert.ok(captionPriority('RESOURCE') < captionPriority('BUILDING'))
  assert.ok(captionPriority('BUILDING') < captionPriority('MONSTER'))
  assert.ok(captionPriority('MONSTER') < captionPriority('MARCH'))
  // 同一点上城与资源挤：留城
  assert.deepEqual(keptKeys([
    plate('MONSTER:m', 'MONSTER', 0, 'Lv5'),
    plate('CITY:c', 'CITY', 0, '无名君主'),
    plate('RESOURCE:r', 'RESOURCE', 0, '铁矿'),
  ]), ['CITY:c'])
})

test('玩家选中的那一张一定留，即使它优先级最低（选中项被藏起来读起来像"点没生效"）', () => {
  assert.deepEqual(keptKeys([
    plate('CITY:c', 'CITY', 0, '无名君主'),
    plate('MARCH:m', 'MARCH', 0, '1200', true),
  ]), ['MARCH:m'])
})

test('结果与输入顺序无关：同一帧重画不能让牌子闪来闪去', () => {
  const candidates = [
    plate('RESOURCE:a', 'RESOURCE', 0, '石料'),
    plate('MONSTER:b', 'MONSTER', CELL, 'Lv5'),
    plate('RESOURCE:c', 'RESOURCE', CELL * 2, '木材'),
    plate('MONSTER:d', 'MONSTER', CELL * 3, 'Lv9'),
  ]
  const forward = keptKeys(candidates)
  const backward = keptKeys([...candidates].reverse())
  assert.deepEqual(backward, forward, '倒序输入选出另一批牌 ⇒ 每帧谁先占位是随机的')
})

test('视口裁剪：顶 HUD、底导航、左右越界和 pinned 牌都不许画进 HUD', () => {
  const viewport: CaptionViewport = { minX: -100, maxX: 100, minY: -80, maxY: 80 }
  assert.deepEqual(keptKeysIn([plateAt('RESOURCE:ok', 'RESOURCE', 0, 0, '石料')], viewport),
    ['RESOURCE:ok'], '安全区中央的牌应保留')
  assert.deepEqual(keptKeysIn([plateAt('RESOURCE:top', 'RESOURCE', 0, 75, '石料')], viewport),
    [], '压进顶 HUD 的牌必须裁掉')
  assert.deepEqual(keptKeysIn([plateAt('RESOURCE:bottom', 'RESOURCE', 0, -95, '石料')], viewport),
    [], '压进底导航的牌必须裁掉')
  assert.deepEqual(keptKeysIn([plateAt('RESOURCE:left', 'RESOURCE', -90, 0, '石料')], viewport),
    [], '左边越界的牌必须裁掉')
  assert.deepEqual(keptKeysIn([plateAt('RESOURCE:right', 'RESOURCE', 90, 0, '石料')], viewport),
    [], '右边越界的牌必须裁掉')
  assert.deepEqual(keptKeysIn([plateAt('RESOURCE:pinned', 'RESOURCE', 0, 75, '石料', true)], viewport),
    [], 'pinned 只保证不被同类挤掉，不能突破 HUD 安全区')
})
