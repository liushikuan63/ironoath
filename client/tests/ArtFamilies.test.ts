/**
 * 职责：素材族数据层的磁盘对账 —— 键表、运行时文件、配置表行三者必须互相咬合。
 * 依赖：node:test / node:assert / node:fs。
 *
 * <p>为什么每条都值得红：
 * ① 键有、文件没有 ⇒ 面板上是一个永远不出图的空位，而且只有玩家看得见；
 * ② 配置表加了新行、映射表没跟上 ⇒ 静默退回 Graphics 占位，没人会去查；
 * ③ 映射表写了不存在的键 ⇒ applyFamilySprite 永远 false，比没有映射更难发现。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  FAMILY_ASSETS, EQUIP_ICON_BY_CONFIG, ITEM_ICON_BY_CONFIG,
  itemArtKeyForConfig, familyArtKey,
} from '../assets/scripts/game/art/ArtFamilies'

const CONFIG_DIR = path.join(repoRoot(), 'contract', 'config')
const RUNTIME_DIR = path.join(repoRoot(), 'client', 'assets', 'resources')

/** 测试默认跑在 client/ 下（test-client.sh 会 cd），但为别的 cwd 也能跑，逐级向上找仓库根。 */
function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'hero.json'))) {
      return dir
    }
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/hero.json，无法做素材对账')
}

function configIds(file: string): string[] {
  const rows = JSON.parse(fs.readFileSync(path.join(CONFIG_DIR, file), 'utf8')).rows
  return rows.map((row: { id: string }) => row.id)
}

test('每个族键都有对应的运行时 PNG（键表 → 磁盘）', () => {
  const missing: string[] = []
  for (const [family, entries] of Object.entries(FAMILY_ASSETS)) {
    for (const [member, rel] of Object.entries(entries)) {
      const disk = path.join(RUNTIME_DIR, `${rel}.png`)
      if (!fs.existsSync(disk)) {
        missing.push(`${family}:${member} → assets/resources/${rel}.png`)
      }
    }
  }
  assert.deepEqual(missing, [], `以下键在磁盘上没有图：\n${missing.join('\n')}`)
})

test('hero.json/activity.json 的行数没有偷偷变化（补族时要同步改本断言）', () => {
  // 立绘/活动族尚未进运行时（等 resources 分包，见 ArtFamilies 注释），
  // 但表行数若变了，下一轮补图的数量就得跟着变 —— 先钉住现状。
  assert.equal(configIds('hero.json').length, 12)
  assert.equal(configIds('activity.json').length, 8)
})

test('item.json 的每个 eq_* 行都在装备映射表里，且目标键真实存在', () => {
  const equips = configIds('item.json').filter((id) => id.startsWith('eq_'))
  assert.equal(equips.length, 16)
  const bad: string[] = []
  for (const id of equips) {
    const key = EQUIP_ICON_BY_CONFIG[id]
    if (key === undefined || !key.startsWith('equip:')
        || FAMILY_ASSETS.equip[key.slice('equip:'.length)] === undefined) {
      bad.push(id)
    }
  }
  assert.deepEqual(bad, [])
})

test('道具映射表的目标键都真实存在；未覆盖的行明确返回 null（不静默指错图）', () => {
  for (const [id, key] of Object.entries(ITEM_ICON_BY_CONFIG)) {
    assert.ok(key.startsWith('item:'), `${id} 指到了非 item 族：${key}`)
    assert.ok(FAMILY_ASSETS.item[key.slice('item:'.length)] !== undefined,
      `${id} → ${key} 在族表里没有这个成员`)
  }
  // 这些行还没有图（技能书/觉醒券/R·N 碎片）—— 必须走 null 占位，而不是蹭一张错图
  for (const id of ['item_hero_skillbook_main', 'item_hero_awaken_1',
    'item_mat_hero_frag_r', 'item_mat_hero_frag_n', 'item_gold_1000']) {
    assert.equal(itemArtKeyForConfig(id), null, `${id} 不该有映射`)
  }
  assert.equal(itemArtKeyForConfig('item_chest_hero'), familyArtKey('item', 'chest_hero'))
  assert.equal(itemArtKeyForConfig('eq_iron_sword'), 'equip:weapon-iron')
})
