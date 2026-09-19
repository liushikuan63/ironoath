/**
 * 职责：素材族数据层的磁盘对账 —— 键表、运行时文件、配置表行、九宫格几何必须互相咬合。
 * 依赖：node:test / node:assert / node:fs。
 *
 * <p>为什么每条都值得红：
 * ① 键有、文件没有 ⇒ 面板上是一个永远不出图的空位，而且只有玩家看得见；
 * ② 配置表加了新行、映射表没跟上 ⇒ 静默退回 Graphics 占位，没人会去查；
 * ③ 映射表写了不存在的键 ⇒ applyFamilySprite 永远 false，比没有映射更难发现；
 * ④ 九宫格的切分几何有两份 ⇒ 后写的那份生效，先写的那份变成"改了没反应"的死字段，
 *    而布局代码按哪一份排都可能把内容压到角饰下面（收口清单 #213 真咬到过）。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  FAMILY_ASSETS, EQUIP_ICON_BY_CONFIG, ITEM_ICON_BY_CONFIG, ACTIVITY_ICON_BY_CONFIG,
  itemArtKeyForConfig, activityIconKey, familyArtKey, PANEL_FRAME_BAND,
} from '../assets/scripts/game/art/ArtFamilies'

const CONFIG_DIR = path.join(repoRoot(), 'contract', 'config')
const RUNTIME_DIR = path.join(repoRoot(), 'client', 'assets', 'resources')
const GENERATED_UI = path.join(RUNTIME_DIR, 'ui', 'generated', 'ui')
const ART_CATALOG_SRC = path.join(repoRoot(), 'client', 'assets', 'scripts', 'scene', 'ArtCatalog.ts')

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

test('hero.json 每一行都有立绘，且键表与磁盘文件一一对应', () => {
  const heroes = configIds('hero.json')
  assert.equal(heroes.length, 12, 'hero.json 行数变了：素材族要同步补图或改本断言')
  const unmapped = heroes.filter((id) => FAMILY_ASSETS.hero[id] === undefined)
  assert.deepEqual(unmapped, [])
})

test('activity.json 八行 ↔ 映射表 ↔ 族键四方咬合（表里多一行少一行都会红在这里）', () => {
  const ids = configIds('activity.json')
  assert.equal(ids.length, 8, 'activity.json 行数变了：素材族要同步补图或改本断言')
  const unmapped = ids.filter((id) => activityIconKey(id) === null)
  assert.deepEqual(unmapped, [], '表里有行没有图标键 —— 界面上就是一个空位')
  const orphans = Object.keys(ACTIVITY_ICON_BY_CONFIG).filter((id) => !ids.includes(id))
  assert.deepEqual(orphans, [], '映射表指到了 activity.json 已删掉的行')
  for (const [id, key] of Object.entries(ACTIVITY_ICON_BY_CONFIG)) {
    assert.ok(key.startsWith('activity:'), `${id} 指到了非 activity 族：${key}`)
    assert.ok(FAMILY_ASSETS.activity[key.slice('activity:'.length)] !== undefined,
      `${id} → ${key} 在族表里没有这个成员`)
  }
  assert.equal(activityIconKey('activity_not_a_real_row'), null, '认不出的行必须退回占位，不能蹭图')
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

/**
 * 取一张图 spriteFrame 子档的四边边框。
 * 子档的 key 是内容哈希（`6c48a`/`f9941` 这类），按 `name` 找才不会一重新导入就对不上。
 */
function frameBorders(pngBaseName: string): Record<'left' | 'top' | 'right' | 'bottom', number> {
  const meta = JSON.parse(
    fs.readFileSync(path.join(GENERATED_UI, `${pngBaseName}.png.meta`), 'utf8'),
  ) as { subMetas: Record<string, { name?: string, userData?: Record<string, unknown> }> }
  const sub = Object.values(meta.subMetas).find((entry) => entry.name === 'spriteFrame')
  assert.ok(sub !== undefined, `${pngBaseName}.png.meta 里没有 spriteFrame 子档`)
  const data = sub.userData ?? {}
  const border = (key: string): number => {
    const value = data[key]
    assert.equal(typeof value, 'number',
      `${pngBaseName}：userData.${key} 不是数字，meta 结构变了 —— 本用例的判据要跟着改`)
    return value as number
  }
  return { left: border('borderLeft'), top: border('borderTop'),
    right: border('borderRight'), bottom: border('borderBottom') }
}

test('薄边 chip 四边都是 12：它是"装饰母版装不下的格子"那一路的唯一尺寸来源', () => {
  for (const name of ['button-chip-v1', 'button-chip-hover-v1', 'button-chip-disabled-v1']) {
    assert.deepEqual(frameBorders(name),
      { left: 12, top: 12, right: 12, bottom: 12 },
      `${name}：边框 12 ⇒ 最小可画 24×24，正好覆盖 46×26 这批小按钮；`
      + '改大就会把 #216 那条"九宫格退化"判据重新引回来')
  }
})

test('面板框的四角带厚只有一个真源：图的 meta border* ↔ 交给布局的 PANEL_FRAME_BAND', () => {
  const band = PANEL_FRAME_BAND
  assert.deepEqual(frameBorders('panel-kingdom-v1'),
    { left: band, top: band, right: band, bottom: band },
    `meta 与 PANEL_FRAME_BAND=${band} 不一致：布局按常量让开一圈，画面按 meta 切一角，`
    + `两者不同值时要么内容压在角饰上，要么白让一圈`)
})

test('ArtCatalog 不许再抄一份九宫格边框（后写的那份会盖掉 meta，让 meta 变成骗人的死字段）', () => {
  const src = fs.readFileSync(ART_CATALOG_SRC, 'utf8')
  assert.equal(/insets\s*:/.test(src), false,
    'SPECS 里又出现了 insets —— 收口清单 #213 拆掉的就是它：它让 #203 那次改 meta 变成空操作')
  assert.equal(/\binset(Left|Right|Top|Bottom)\s*=[^=]/.test(src), false,
    '运行期改写了 SpriteFrame 的边框 ⇒ meta 里的 border 从此不影响画面')
})

test('运行时的 ui 图只剩三族：面板框、chip、页签（装饰母版已因零消费退出包）', () => {
  const pngs = fs.readdirSync(GENERATED_UI).filter((name) => name.endsWith('.png')).sort()
  assert.deepEqual(pngs, [
    'button-chip-disabled-v1.png', 'button-chip-hover-v1.png', 'button-chip-v1.png',
    'nav-tab-selected-v1.png', 'nav-tab-v1.png', 'panel-kingdom-v1.png',
  ], '包里多了/少了 ui 图 —— 加图要连同消费点与判据一起进来，删图要确认零消费（#216 的口径）')
})
