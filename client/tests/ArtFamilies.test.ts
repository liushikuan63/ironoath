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
  PANEL_IRON_INSET,
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

test('统一v3五种底板使用现采用512×326薄框，四边24与布局镜像一致，不残留粗框混搭', () => {
  assert.deepEqual(PANEL_IRON_INSET, { left: 24, right: 24, top: 24, bottom: 24 })
  assert.equal(PANEL_FRAME_BAND, 24)
  for (const name of ['panel-iron-v1', 'panel-parchment-v1', 'panel-warning-v1', 'panel-gilt-v1', 'panel-kingdom-v1']) {
    const png = fs.readFileSync(path.join(GENERATED_UI, `${name}.png`))
    assert.equal(png.readUInt32BE(16), 512, `${name} 未同步采用版宽度`)
    assert.equal(png.readUInt32BE(20), 326, `${name} 未同步采用版高度`)
    assert.deepEqual(frameBorders(name), PANEL_IRON_INSET,
      `${name} meta 与布局镜像不一致：采用版角帽约16–18px，四边24保角并让正文离开铜线`)
  }
})

test('ArtCatalog 不许再抄一份九宫格边框（后写的那份会盖掉 meta，让 meta 变成骗人的死字段）', () => {
  const src = fs.readFileSync(ART_CATALOG_SRC, 'utf8')
  assert.equal(/insets\s*:/.test(src), false,
    'SPECS 里又出现了 insets —— 收口清单 #213 拆掉的就是它：它让 #203 那次改 meta 变成空操作')
  assert.equal(/\binset(Left|Right|Top|Bottom)\s*=[^=]/.test(src), false,
    '运行期改写了 SpriteFrame 的边框 ⇒ meta 里的 border 从此不影响画面')
})

test('地形图集16格都进入实际地图选择，重绘及负坐标不改变变体或落到目录外', () => {
  const catalog = fs.readFileSync(ART_CATALOG_SRC, 'utf8')
  const keyBlock = /export type TerrainArtKey =([\s\S]*?)\r?\n\r?\n/.exec(catalog)?.[1]
  assert.ok(keyBlock !== undefined, '找不到 TerrainArtKey 声明，无法核对图集与消费键')
  const terrainKeys = new Set(Array.from(keyBlock.matchAll(/'map\.terrain\.(\d+)'/g),
    (match) => Number(match[1])))
  assert.deepEqual(Array.from(terrainKeys).sort((a, b) => a - b),
    Array.from({ length: 16 }, (_, index) => index), '4×4图集不能只声明前两行的键')
  const columns = Number(/const TERRAIN_COLUMNS = (\d+)/.exec(catalog)?.[1])
  const rows = Number(/const TERRAIN_ROWS_USED = (\d+)/.exec(catalog)?.[1])
  const countExpression = /^export const TERRAIN_VARIANT_COUNT = (.+)$/m.exec(catalog)?.[1]
  assert.ok(countExpression !== undefined, '地图与目录必须共享生产变体总数')
  const variantCount = new Function('TERRAIN_COLUMNS', 'TERRAIN_ROWS_USED',
    `return (${countExpression})`)(columns, rows) as number
  assert.equal(columns * rows, variantCount, '变体总数必须包含图集的全部四行')
  assert.equal(variantCount, terrainKeys.size, '图集裁帧总数必须与地形键覆盖数一致')

  // 场景依赖 cc，不能直接 import；只执行生产代码中这一段无引擎、无副作用的坐标映射。
  const mapSource = fs.readFileSync(path.join(repoRoot(),
    'client/assets/scripts/scene/WorldMap.ts'), 'utf8')
  const body = /^function terrainVariantForChunk\(cx: number, cy: number\): number \{([\s\S]*?)^\}/m
    .exec(mapSource)?.[1]
  assert.ok(body !== undefined, '找不到生产坐标映射，不能以另一份算法代替真实选择器')
  const variant = new Function('cx', 'cy', 'TERRAIN_VARIANT_COUNT', body) as
    (cx: number, cy: number, count: number) => number
  const seen = new Set<number>()
  for (let cx = -16; cx < 16; cx++) {
    for (let cy = -16; cy < 16; cy++) {
      const chosen = variant(cx, cy, variantCount)
      assert.ok(terrainKeys.has(chosen), `块(${cx},${cy})选择了未登记的地形${chosen}`)
      assert.equal(variant(cx, cy, variantCount), chosen, '同一坐标重绘不得跳变')
      seen.add(chosen)
    }
  }
  assert.deepEqual(Array.from(seen).sort((a, b) => a - b),
    Array.from(terrainKeys).sort((a, b) => a - b), '后两行裁帧加载了却没有进入实际地图选择')
})

test('包里的 ui 图逐张点名，多一张少一张都必须更新消费点与判据', () => {
  const pngs = fs.readdirSync(GENERATED_UI).filter((name) => name.endsWith('.png')).sort()
  assert.deepEqual(pngs, [
    'button-chip-disabled-v1.png', 'button-chip-hover-v1.png', 'button-chip-v1.png',
'button-iron-hover-v1.png', 'button-iron-v1.png',
    'chip-close-v1.png',
    'crest-battle-v1.png', 'crest-league-v1.png', 'crest-nation-v1.png',
    'nav-tab-selected-v1.png', 'nav-tab-v1.png',
    'panel-gilt-v1.png', 'panel-iron-v1.png', 'panel-kingdom-v1.png',
    'panel-parchment-v1.png', 'panel-warning-v1.png',
    'plate-band-v1.png', 'plate-tooltip-v1.png',
    'seal-wax-v1.png',
  ], '包里多了/少了 ui 图 —— 加图要连同消费点与判据一起进来，删图要确认零消费（#216 的口径）')
})

/** 规格 §二 的档位（A 底板 / B 条行 / C 小件 / 装饰件不参与九宫格）。 */
type Tier = 'A' | 'B' | 'C' | 'decor'

/**
 * §二 三档契约里**登记层能被机器判到的那两维**：该档允许的 png 前缀、该档的 border。
 * 「消费尺寸下限」刻意不在这里判 —— 那是 §七 的待裁决项（现跑 V25-c 已接的体力弹层是 360×260，
 * 低于 §二 写的 460×300），拿未裁决的数当判据会把别人的在途文件判红。
 */
const TIER_CONTRACT: Record<Tier, {
  border: { left: number, top: number, right: number, bottom: number } | null
  pngPrefixes: string[]
}> = {
  // 统一v3采用版角帽约16–18px；现meta四边24保角，不能继续用旧80·72粗框合同。
  A: { border: { ...PANEL_IRON_INSET }, pngPrefixes: ['panel-'] },
  B: { border: { left: 12, top: 8, right: 12, bottom: 8 }, pngPrefixes: ['plate-'] },
  // 'plate-tooltip-' 是 C 档里唯一的例外前缀：它是浮动提示条（256×61、border 6·4），
  // 归 C 不归 B —— B 档条行是 512×52 的 12·8 薄边，两者 border 差一倍，混档就是 §八.2 判的那件事。
  C: {
    border: { left: 6, top: 4, right: 6, bottom: 4 },
    pngPrefixes: ['button-', 'chip-', 'plate-tooltip-'],
  },
  decor: { border: null, pngPrefixes: ['banner-', 'crest-', 'seal-', 'divider-'] },
}

/** §三 清单里 V25 那一族的 ArtKey → 档。键还没接线时可以暂不列，列进来就必须与目录一致。 */
const V25_KEY_TIER: Record<string, Tier> = {
  'ui.panel.kingdom': 'A',
  'ui.panel.iron': 'A',
  'ui.plate.band': 'B',
  'ui.button.iron': 'C',
  'ui.chip.close': 'C',
  'ui.plate.tooltip': 'C',
  'ui.panel.parchment': 'A',
  'ui.panel.warning': 'A',
  'ui.panel.gilt': 'A',
  'ui.button.iron.hover': 'C',
  'ui.crest.league': 'decor',
  'ui.crest.nation': 'decor',
  'ui.crest.battle': 'decor',
  'ui.seal.wax': 'decor',
}

/** V25 之前的遗留件：不参与 §二 的 border 契约，但点名登记，免得"没在表里"变成"没人管"。 */
const LEGACY_UI_PNGS = [
  'button-chip-v1.png', 'button-chip-hover-v1.png', 'button-chip-disabled-v1.png',
  'nav-tab-v1.png', 'nav-tab-selected-v1.png',
]

/**
 * 从 `ArtCatalog` 的 `SPECS` 现取「键 → 资源路径」。
 * 不在测试里抄第二份表：抄了就会与真源漂移（#213 那次"两份数字"的同一个形状）。
 */
function specsFromCatalog(): Map<string, string> {
  const src = fs.readFileSync(ART_CATALOG_SRC, 'utf8')
  const out = new Map<string, string>()
  const re = /'([\w.]+)':\s*\{[^}]*?path:\s*'([^']+)'/g
  let m = re.exec(src)
  while (m !== null) {
    const [, key, res] = m
    if (key !== undefined && res !== undefined) {
      out.set(key, res)
    }
    m = re.exec(src)
  }
  return out
}

test('V25 档位登记表：一个键只有一个档，它登记的 png 与那张图的 meta border 必须属于那个档（§八.2 跨档复用在登记层判掉）', () => {
  const specs = specsFromCatalog()
  const registered = Object.entries(V25_KEY_TIER).filter(([key]) => specs.has(key))
  assert.ok(registered.length >= 3,
    `档位表里 ${Object.keys(V25_KEY_TIER).length} 个键，ArtCatalog 只认到 ${registered.length} 个`
    + ' —— 表与目录脱节时本用例等于没判，先修表或修目录')
  const pngTier = new Map<string, string>()
  const bad: string[] = []
  for (const [key, tier] of registered) {
    const res = specs.get(key) as string
    const base = `${path.basename(res)}.png`
    const contract = TIER_CONTRACT[tier]
    if (!contract.pngPrefixes.some((prefix) => base.startsWith(prefix))) {
      bad.push(`${key} 登记为 ${tier} 档，指的却是 ${base}`
        + `（该档只允许前缀 ${contract.pngPrefixes.join(' / ')}）`)
    }
    const shared = pngTier.get(base)
    if (shared !== undefined && shared !== tier) {
      bad.push(`${base} 同时被 ${shared} 档与 ${tier} 档的键登记 = 一张图跨两档用`)
    }
    pngTier.set(base, tier)
    if (!fs.existsSync(path.join(GENERATED_UI, base))) {
      bad.push(`${key} → ${base} 在盘上没有这张图`)
      continue
    }
    if (contract.border === null) {
      continue
    }
    const border = frameBorders(path.basename(res))
    for (const side of ['left', 'top', 'right', 'bottom'] as const) {
      if (border[side] !== contract.border[side]) {
        bad.push(`${key}（${tier} 档）的 ${base} meta border.${side}=${border[side]}，`
          + `§二 契约要 ${contract.border[side]} —— border 换了档就等于素材换了档`)
      }
    }
  }
  assert.deepEqual(bad, [])
})

test('每张在盘的 ui 图都必须被某个 ArtKey 登记；遗留件名单里不许留已消失的条目', () => {
  // 上面那条「只剩三族」钉的是**文件清单**，换掉文件名它就看不见 ⇒ 这条钉的是**消费**：
  // 零消费素材进包（V25-b 的 plate-band 就是这么被拦回去的）此前只在人工环节判过。
  const specs = specsFromCatalog()
  const registered = new Set<string>()
  for (const [key, res] of specs) {
    if (!res.startsWith('ui/generated/ui/')) {
      continue
    }
    registered.add(`${path.basename(res)}.png`)
    assert.ok(fs.existsSync(path.join(GENERATED_UI, `${path.basename(res)}.png`)),
      `键 ${key} 指的 ${res} 不在盘上（面板上就是一个永远不出图的空位）`)
  }
  const pngs = fs.readdirSync(GENERATED_UI).filter((name) => name.endsWith('.png'))
  const unowned = pngs.filter((name) => !registered.has(name) && !LEGACY_UI_PNGS.includes(name))
  assert.deepEqual(unowned, [], `这些 ui 图没有任何 ArtKey 消费：${unowned.join(', ')} —— 退回 art-src 草稿区，别占分包`)
  const gone = LEGACY_UI_PNGS.filter((name) => !pngs.includes(name))
  assert.deepEqual(gone, [], `遗留件名单里有已不存在的条目：${gone.join(', ')} —— 删掉它，`
    + '否则下一张同前缀的新图会蹭到"不用过档位表"的豁免')
})
