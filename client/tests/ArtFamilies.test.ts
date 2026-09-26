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
  itemArtKeyForConfig, activityIconKey, buildingArtKey, familyArtKey, PANEL_FRAME_BAND,
  CITY_STAGE_ASSETS,
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

test('building.json 每一行都有内城正稿键，且键表没有指向已删掉的行', () => {
  // A18：城景化之后格子上的图就是这一族。少一行的症状是"那栋楼退回图集小图标"，
  // 在满屏正稿里很显眼，但只有真跑才看得见 —— 所以在这里钉住。
  const ids = configIds('building.json')
  const noArt = ids.filter((id) => buildingArtKey(id) === null)
  assert.deepEqual(noArt, [], 'building.json 里有行没有正稿 —— 补图，别让它退回小图标')
  const orphans = Object.keys(FAMILY_ASSETS.building).filter((member) => !ids.includes(member))
  assert.deepEqual(orphans, [], '建筑族里挂着 building.json 已删掉的行（白占分包体积）')
})

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

test('A17 舞台三件套都有运行时 PNG，且不会被当成按需族漏加载', () => {
  const missing = Object.entries(CITY_STAGE_ASSETS)
    .filter(([, rel]) => !fs.existsSync(path.join(RUNTIME_DIR, `${rel}.png`)))
    .map(([name, rel]) => `${name} → assets/resources/${rel}.png`)
  assert.deepEqual(missing, [], `A17 舞台图缺件：\n${missing.join('\n')}`)
  const source = fs.readFileSync(ART_CATALOG_SRC, 'utf8')
  for (const member of Object.keys(CITY_STAGE_ASSETS)) {
    assert.ok(source.includes(`CITY_STAGE_ASSETS.${member}`),
      `ArtCatalog 没有消费 A17 常量：${member}`)
  }
  assert.ok(source.includes("'city.ground'") && source.includes("'city.wall'")
    && source.includes("'city.ridge'"), 'ArtCatalog 缺少 A17 的静态键')
  assert.equal(/key === 'city\.ground'[\s\S]{0,120}enableTextureRepeat/.test(source),
    true, 'A17 地表砖没有启用 REPEAT，TILED 放大时会出现边缘拉丝')
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

test('道具映射表的目标键都真实存在；item.json 每一行都有图（A10/A11 后覆盖满了）', () => {
  // 目标键有两种来源：族图（`item:` / `equip:`）与启动图标图集（`icon:`，A11 的资源箱走这条）。
  // 两种都要对账到真实存在的文件 —— 只认前一种会让"复用图集图标"这条正路被自己的测试判违规。
  const atlasKeys = new Set(
    (JSON.parse(fs.readFileSync(path.join(RUNTIME_DIR,
      'ui/generated/icons/icons-atlas.json'), 'utf8')).items as { key: string }[])
      .map((item) => item.key),
  )
  for (const [id, key] of Object.entries(ITEM_ICON_BY_CONFIG)) {
    if (key.startsWith('icon:')) {
      assert.ok(atlasKeys.has(key.slice('icon:'.length)),
        `${id} → ${key} 在启动图标图集里没有这一枚（icons-atlas.json 对不上）`)
      continue
    }
    assert.ok(key.startsWith('item:'), `${id} 指到了非 item 族：${key}`)
    assert.ok(FAMILY_ASSETS.item[key.slice('item:'.length)] !== undefined,
      `${id} → ${key} 在族表里没有这个成员`)
  }
  // A10（4 张原创 + 2 张调色派生）与 A11（5 行复用图集图标）把最后 9 行无图行补齐了。
  // 从此这条不是"记着哪些行还没图"，而是**覆盖满则新行必须登记**：
  // item.json 加一行而映射表没跟上 ⇒ 背包里是一个 Graphics 占位方块，只有玩家看得见。
  const noIcon = configIds('item.json').filter((id) => itemArtKeyForConfig(id) === null)
  assert.deepEqual(noIcon, [], 'item.json 里有行没有图标键 —— 补图或复用图集图标，别让它退回占位')
  // 反向也要咬住：映射表指到 item.json 已删掉的行，是一句永远不会命中的死代码
  const orphans = Object.keys(ITEM_ICON_BY_CONFIG).filter((id) => id.startsWith('item_')
    && !configIds('item.json').includes(id))
  assert.deepEqual(orphans, [], '映射表指到了 item.json 已删掉的行')
  // A11 的五行按文档"零新增纹理"复用图集资源图标，包装量由文字 Label 表达
  assert.equal(itemArtKeyForConfig('item_gold_1000'), 'icon:resources/gold')
  assert.equal(itemArtKeyForConfig('item_res_wood_10k'), 'icon:resources/wood')
  // A10 的新行确实指到自己的图，不是蹭了一张近亲
  assert.equal(itemArtKeyForConfig('item_mat_hero_frag_r'), familyArtKey('item', 'shard_r'))
  assert.equal(itemArtKeyForConfig('item_hero_skillbook_main'),
    familyArtKey('item', 'skillbook_main'))
  assert.equal(itemArtKeyForConfig('item_chest_hero'), familyArtKey('item', 'chest_hero'))
  assert.equal(itemArtKeyForConfig('eq_iron_sword'), 'equip:weapon-iron')
  assert.equal(itemArtKeyForConfig('item_not_a_real_row'), null,
    '认不出的 id 必须退回 null（占位），不能蹭一张图')
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
