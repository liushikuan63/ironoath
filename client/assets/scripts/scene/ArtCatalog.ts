/**
 * 职责：运行期美术资源目录 —— 统一加载 `resources/ui/generated/**` 下的 SpriteFrame。
 * 依赖：cc（resources / Sprite / SpriteFrame / UITransform）。
 *
 * <p>这层存在的意义是把「资源路径、加载失败兜底」收在一个地方。
 * 面板代码只写 `applySlicedSprite(node, 'ui.panel.kingdom', ...)`，
 * 不直接拼 `resources.load` 路径，也不自己判断该用普通图还是九宫格。
 *
 * <p><b>九宫格的切分几何不在这里</b>：它属于素材本身，写在各图的 `.png.meta`（border*）。
 * 这里曾经有一份 `insets` 覆盖，而它在 `loadOne` 里<b>后写且生效</b> —— 于是"改 meta 的 border"
 * 这件事对运行期的画面一个像素都不影响，布局代码里的带厚又和它不一致（收口清单 #213）。
 */

import {
  Graphics, JsonAsset, Node, Rect, resources, Size, Sprite, SpriteFrame, Texture2D,
  UITransform, Vec2,
} from 'cc'
import { ArtFamily, CITY_STAGE_ASSETS, FAMILY_ASSETS } from '../game/art/ArtFamilies'

type StaticArtKey =
  | 'ui.panel.kingdom'
  | 'ui.button.chip'
  | 'ui.button.chip.hover'
  | 'ui.button.chip.disabled'
  | 'ui.nav.tab'
  | 'ui.nav.tab.selected'
  | 'city.ridge'
  | 'city.ground'
  | 'city.wall'
  | 'city.scene.reference'
  | 'map.terrain.grass'
  | 'map.entity.city'
  | 'map.entity.monster'
  | 'map.entity.resource'
  | 'map.entity.allianceBuilding'
  | 'map.entity.march'

export type IconArtKey =
  | 'icon:buildings/academy'
  | 'icon:buildings/archery-range'
  | 'icon:buildings/barracks'
  | 'icon:buildings/drill-ground'
  | 'icon:buildings/embassy'
  | 'icon:buildings/farm'
  | 'icon:buildings/hospital'
  | 'icon:buildings/iron-mine'
  | 'icon:buildings/lumber-camp'
  | 'icon:buildings/main-city'
  | 'icon:buildings/quarry'
  | 'icon:buildings/siege-workshop'
  | 'icon:buildings/stable'
  | 'icon:buildings/wall'
  | 'icon:buildings/warehouse'
  | 'icon:rarity/n'
  | 'icon:rarity/r'
  | 'icon:rarity/sr'
  | 'icon:rarity/ssr'
  | 'icon:resources/gold'
  | 'icon:resources/grain'
  | 'icon:resources/iron'
  | 'icon:resources/stamina'
  | 'icon:resources/stone'
  | 'icon:resources/wood'
  | 'icon:units/archer'
  | 'icon:units/cavalry'
  | 'icon:units/infantry'
  | 'icon:units/siege'

export type TerrainArtKey =
  | 'map.terrain.0'
  | 'map.terrain.1'
  | 'map.terrain.2'
  | 'map.terrain.3'
  | 'map.terrain.4'
  | 'map.terrain.5'
  | 'map.terrain.6'
  | 'map.terrain.7'

export type ArtKey = StaticArtKey | IconArtKey | TerrainArtKey
/**
 * 按钮的三态。`hover` 在这里的含义是"这一格是当前选中项"，不是鼠标悬停；
 * `disabled` 是"点不动"（灰掉但仍是按钮形状）。没有 pressed：全仓库零消费，
 * 要做触摸反馈得先补图并同步 derive_button_states.py 的 STATES。
 */
export type CommandButtonState = 'normal' | 'hover' | 'disabled'

interface ArtSpec {
  readonly path: string
}

interface IconAtlasItem {
  readonly key: string
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
}

interface IconAtlasIndex {
  readonly items: readonly IconAtlasItem[]
}

const ICON_ATLAS_PATH = 'ui/generated/icons/icons-atlas'
const TERRAIN_ATLAS_PATH = 'ui/generated/map/terrain-atlas-v1'
const TERRAIN_GRASS_PATH = 'ui/generated/map/terrain-grass-v1'
const TERRAIN_COLUMNS = 4
/** 图集有 4×4 格；四行都要用，否则山岩与荒地在运行时永远不会出现。 */
const TERRAIN_ROWS_USED = 4
const TERRAIN_VARIANT_COUNT = TERRAIN_COLUMNS * TERRAIN_ROWS_USED

const SPECS: Record<StaticArtKey, ArtSpec> = {
  'ui.panel.kingdom': {
    path: 'ui/generated/ui/panel-kingdom-v1',
  },
  'ui.button.chip': {
    path: 'ui/generated/ui/button-chip-v1',
  },
  'ui.button.chip.hover': {
    path: 'ui/generated/ui/button-chip-hover-v1',
  },
  'ui.button.chip.disabled': {
    path: 'ui/generated/ui/button-chip-disabled-v1',
  },
  'ui.nav.tab': {
    path: 'ui/generated/ui/nav-tab-v1',
  },
  'ui.nav.tab.selected': {
    path: 'ui/generated/ui/nav-tab-selected-v1',
  },
  'city.ridge': { path: CITY_STAGE_ASSETS.ridge },
  'city.ground': { path: CITY_STAGE_ASSETS.ground },
  'city.wall': { path: CITY_STAGE_ASSETS.wall },
  'city.scene.reference': { path: CITY_STAGE_ASSETS.reference },
  'map.terrain.grass': { path: TERRAIN_GRASS_PATH },
  'map.entity.city': { path: 'ui/generated/map/map-player-city-v1' },
  'map.entity.monster': { path: 'ui/generated/map/map-monster-camp-v1' },
  'map.entity.resource': { path: 'ui/generated/map/map-resource-point-v1' },
  'map.entity.allianceBuilding': { path: 'ui/generated/map/map-alliance-building-v1' },
  'map.entity.march': { path: 'ui/generated/map/map-march-marker-v1' },
}

const frames = new Map<ArtKey, SpriteFrame>()
let preloadPromise: Promise<boolean> | null = null

/**
 * 新素材族（G1~G7，见 art-src/素材缺口清单.md）**不进启动预载**：
 * 首包预算与 bootMs 都只养得起现有 40 键；族图由消费面板在打开前 ensureFamily 一次，
 * 加载失败保留 Graphics 占位（与 loadOne 同一兜底纪律）。
 */
const familyFrames = new Map<string, SpriteFrame>()
const familyLoads = new Map<ArtFamily, Promise<number>>()

export function ensureFamily(family: ArtFamily): Promise<number> {
  const cached = familyLoads.get(family)
  if (cached !== undefined) {
    return cached
  }
  const run = (async () => {
    const entries = Object.entries(FAMILY_ASSETS[family])
    const results: number[] = await Promise.all(entries.map(([member, path]) =>
      loadFrame(path).then((frame) => {
        if (frame === null) {
          console.warn(`[ArtCatalog] 族资源加载失败：${family}:${member}`)
          return 0
        }
        familyFrames.set(`${family}:${member}`, frame)
        return 1
      })))
    return results.reduce((a, b) => a + b, 0)
  })()
  familyLoads.set(family, run)
  return run
}

export function familyFrame(key: string): SpriteFrame | null {
  return familyFrames.get(key) ?? null
}

/** 用族图铺满节点（SIMPLE）；键未加载或加载失败返回 false，调用方继续走 Graphics 占位。 */
export function applyFamilySprite(node: Node, key: string, width: number, height: number): boolean {
  const frame = familyFrame(key)
  if (frame === null) {
    return false
  }
  paintSprite(node, frame, Sprite.Type.SIMPLE, width, height)
  return true
}

/**
 * 行图标统一入口：`icon:` 前缀走启动预载的图标图集，其余（`item:`/`equip:` 等）走按需族图。
 * 背包这类"两种来源混排"的列表只认这一个函数，避免每个面板自己分辨前缀。
 */
export function applyAnyIconSprite(node: Node, key: string, width: number, height: number): boolean {
  if (key.startsWith('icon:')) {
    return applyIconSprite(node, key as IconArtKey, width, height)
  }
  return applyFamilySprite(node, key, width, height)
}

/**
 * 预加载第一套资源。任何单张失败都只告警并保留 Graphics 兜底，不阻断登录。
 */
export function preloadRuntimeArt(): Promise<boolean> {
  if (preloadPromise === null) {
    preloadPromise = loadAll()
  }
  return preloadPromise
}

async function loadAll(): Promise<boolean> {
  const entries = Object.entries(SPECS) as Array<[StaticArtKey, ArtSpec]>
  const [basic, icons, terrain] = await Promise.all([
    Promise.all(entries.map(([key, spec]) => loadOne(key, spec))),
    loadIconAtlas(),
    loadTerrainAtlas(),
  ])
  const loaded = basic.filter(Boolean).length + icons + terrain
  const expected = entries.length + 29 + TERRAIN_VARIANT_COUNT
  if (loaded !== expected) {
    console.warn(`[ArtCatalog] 美术资源只加载 ${loaded}/${expected} 张，缺失项继续使用 Graphics 占位`)
  }
  return loaded > 0
}

function loadOne(key: StaticArtKey, spec: ArtSpec): Promise<boolean> {
  return loadFrame(spec.path).then((frame) => {
    if (frame === null) {
      console.warn(`[ArtCatalog] 资源加载失败：${key}`)
      return false
    }
    // A17 地表砖是 TILED 用法，目标通常大于 512×512；不改成 REPEAT 会把边缘拉成条带。
    if (key === 'city.ground') {
      enableTextureRepeat(frame)
    }
    frames.set(key, frame)
    return true
  })
}

function loadFrame(path: string): Promise<SpriteFrame | null> {
  return new Promise((resolve) => {
    resources.load(`${path}/spriteFrame`, SpriteFrame, (error, frame) => {
      if (error !== null && error !== undefined) {
        console.warn(`[ArtCatalog] 资源加载失败：${path}`, error)
        resolve(null)
        return
      }
      resolve(frame)
    })
  })
}

function loadJson(path: string): Promise<unknown | null> {
  return new Promise((resolve) => {
    resources.load(path, JsonAsset, (error, asset) => {
      if (error !== null && error !== undefined) {
        console.warn(`[ArtCatalog] JSON 加载失败：${path}`, error)
        resolve(null)
        return
      }
      resolve(asset.json)
    })
  })
}

async function loadIconAtlas(): Promise<number> {
  const [base, json] = await Promise.all([
    loadFrame(ICON_ATLAS_PATH),
    loadJson(ICON_ATLAS_PATH),
  ])
  if (base === null || json === null) {
    return 0
  }
  const index = json as Partial<IconAtlasIndex>
  if (!Array.isArray(index.items)) {
    console.warn('[ArtCatalog] 图标图集索引缺少 items')
    return 0
  }
  let loaded = 0
  for (const item of index.items) {
    if (!validAtlasItem(item)) {
      continue
    }
    // 索引与 SpriteFrame.rect 都直接使用原图的行列坐标，不能再做一次高度翻转。
    const frame = subFrame(base, item.x, item.y, item.width, item.height)
    frames.set(iconArtKey(item.key), frame)
    loaded++
  }
  return loaded
}

async function loadTerrainAtlas(): Promise<number> {
  const base = await loadFrame(TERRAIN_ATLAS_PATH)
  if (base === null) {
    return 0
  }
  // 一格是 128×128 而地图块远大于一格，TILED 会走到 UV 的 [0,1] 之外；
  // 纹理保持 clamp 就会把边缘像素拉成条带，必须显式改成重复采样。
  enableTextureRepeat(base)
  const cellWidth = Math.floor(base.originalSize.width / TERRAIN_COLUMNS)
  const cellHeight = Math.floor(base.originalSize.height / 4)
  let loaded = 0
  for (let row = 0; row < TERRAIN_ROWS_USED; row++) {
    for (let column = 0; column < TERRAIN_COLUMNS; column++) {
      const index = row * TERRAIN_COLUMNS + column
      const frame = subFrame(base, column * cellWidth,
        row * cellHeight, cellWidth, cellHeight)
      frames.set(terrainArtKey(index), frame)
      loaded++
    }
  }
  return loaded
}

function enableTextureRepeat(frame: SpriteFrame): void {
  const texture = frame.texture as Texture2D | null
  if (texture === null || texture === undefined
    || typeof texture.setWrapMode !== 'function') {
    console.warn('[ArtCatalog] 地形纹理不支持重复采样，TILED 可能被拉伸')
    return
  }
  texture.setWrapMode(Texture2D.WrapMode.REPEAT, Texture2D.WrapMode.REPEAT)
}

function subFrame(base: SpriteFrame, x: number, y: number,
                  width: number, height: number): SpriteFrame {
  const frame = new SpriteFrame()
  frame.texture = base.texture
  frame.rect = new Rect(x, y, width, height)
  frame.originalSize = new Size(width, height)
  frame.offset = new Vec2(0, 0)
  frame.packable = false
  return frame
}

function validAtlasItem(item: IconAtlasItem): boolean {
  return typeof item.key === 'string'
    && Number.isFinite(item.x)
    && Number.isFinite(item.y)
    && Number.isFinite(item.width)
    && Number.isFinite(item.height)
    && item.width > 0
    && item.height > 0
}

export function artFrame(key: ArtKey): SpriteFrame | null {
  return frames.get(key) ?? null
}

export function hasArt(key: ArtKey): boolean {
  return frames.has(key)
}

export function iconArtKey(key: string): IconArtKey {
  return `icon:${key}` as IconArtKey
}

export function terrainArtKey(index: number): TerrainArtKey {
  const normalized = ((index % TERRAIN_VARIANT_COUNT) + TERRAIN_VARIANT_COUNT)
    % TERRAIN_VARIANT_COUNT
  return `map.terrain.${normalized}` as TerrainArtKey
}

export function buildingIconKey(configId: string): IconArtKey {
  const key = configId.replace(/^building_/, '').replace(/_/g, '-')
  return iconArtKey(`buildings/${key}`)
}

export function resourceIconKey(type: string): IconArtKey {
  return iconArtKey(`resources/${type.toLowerCase()}`)
}

export function unitIconKey(type: string): IconArtKey {
  return iconArtKey(`units/${type.toLowerCase()}`)
}

export function rarityIconKey(rarity: string): IconArtKey {
  return iconArtKey(`rarity/${rarity.toLowerCase()}`)
}

/** 用九宫格 Sprite 替换节点背景；加载失败时返回 false，调用方继续走 Graphics 兜底。 */
export function applySlicedSprite(node: Node, key: ArtKey, width: number, height: number): boolean {
  return applySprite(node, key, Sprite.Type.SLICED, width, height)
}

/** 用 TILED Sprite 铺满节点；地图地面用它。
 *
 * <p>`tileScale` 把平铺周期放大：地貌块是 256² 的整幅画，按原生尺寸平铺时
 * 同一个山形每 ~95px 重复一次，整张地图读成壁纸。放大周期不破坏无缝性
 * （平铺的纹理放大后仍然无缝），只是让重复稀疏到读不出网格。 */
export function applyTiledSprite(node: Node, key: ArtKey, width: number, height: number,
                                 tileScale = 1): boolean {
  return applySprite(node, key, Sprite.Type.TILED, width, height, tileScale)
}

/** 用 SIMPLE Sprite 替换节点画面；地图实体与行军图标用它。 */
export function applySimpleSprite(node: Node, key: ArtKey, width: number, height: number): boolean {
  return applySprite(node, key, Sprite.Type.SIMPLE, width, height)
}

export function applyIconSprite(node: Node, key: IconArtKey, width: number, height: number): boolean {
  return applySimpleSprite(node, key, width, height)
}

export function applyTerrainSprite(node: Node, index: number,
                                   width: number, height: number, tileScale = 1): boolean {
  return applyTiledSprite(node, terrainArtKey(index), width, height, tileScale)
}

/**
 * 游戏里的按钮一律走这张薄边 chip（meta 边框 12 ⇒ 最小可画 24×24，覆盖 46×26 ~ 132×34 全部消费点）。
 *
 * <p>原来这里是一张 384×143 的装饰母版，左右端帽各 54、上下边框各 40 —— 而实际格子最高的只有 34，
 * 也就是说**它从来没有被九宫格画过**，引擎只能整图缩小，观感就是一团缩小的花纹（#216 的
 * `degenerateSlices` 判据当场列出 24 个消费点全中）。母版四态图合计 **300KB**（分包 1.65MB 的 18%）
 * 在改完之后就剩零消费，已退出运行时；母稿仍在 `art-src`，将来真出现 ≥120×88 的大按钮再收回来，
 * 不要提前留在包里。
 *
 * <p>三张状态图都由同一张常态**派生**（`art-src/derive_button_states.py`）：两次生成必然漂移造型，
 * 摆在一起就是两套按钮。没有 pressed —— 全仓库零消费，要做得先补图并同步这里与 STATES。
 */
function chipArtKey(state: CommandButtonState): StaticArtKey {
  if (state === 'hover') {
    return 'ui.button.chip.hover'
  }
  return state === 'disabled' ? 'ui.button.chip.disabled' : 'ui.button.chip'
}
export function applyCommandButton(node: Node, state: CommandButtonState,
                                   width: number, height: number): boolean {
  return applySlicedSprite(node, chipArtKey(state), width, height)
}

/**
 * 底部导航格的页签图。刻意**不**走九宫格：格子尺寸由条宽 ÷ 格数定死（≈63×44），
 * 而按钮母版的端帽就有 54px —— 九宫格在那个尺寸上退化成"整张图缩小"，画出来是一朵花
 * （收口清单 #215 的截图）。页签母版本来就是按这个比例画的，等比铺满即可。
 */
export function applyNavTab(node: Node, selected: boolean, width: number, height: number): boolean {
  return applySimpleSprite(node, selected ? 'ui.nav.tab.selected' : 'ui.nav.tab', width, height)
}

/** 回到 Graphics 画面。池化节点在不同实体之间复用时必须显式切回，避免残留上一张图。 */
export function clearSprite(node: Node): void {
  const sprite = node.getComponent(Sprite)
  if (sprite !== null) {
    sprite.enabled = false
  }
  const graphics = node.getComponent(Graphics)
  if (graphics !== null) {
    graphics.enabled = true
  }
}

function applySprite(node: Node, key: ArtKey, type: number,
                     width: number, height: number, tileScale = 1): boolean {
  const frame = artFrame(key)
  if (frame === null) {
    clearSprite(node)
    return false
  }
  paintSprite(node, frame, type, width, height, tileScale)
  return true
}

/** 把一张 SpriteFrame 铺到节点上，并显式关掉 Graphics 底画。 */
function paintSprite(node: Node, frame: SpriteFrame, type: number,
                     width: number, height: number, tileScale = 1): void {
  const graphics = node.getComponent(Graphics)
  const sprite = node.getComponent(Sprite) ?? node.addComponent(Sprite)
  sprite.spriteFrame = frame
  sprite.type = type
  sprite.sizeMode = Sprite.SizeMode.CUSTOM
  sprite.enabled = true
  // TILED 的平铺周期跟着节点缩放走：盒子缩到 1/scale、节点放大 scale，
  // 覆盖面积不变而 motif 变大。SIMPLE 一律复位到 1，免得池化复用时带上一次的缩放。
  const scaled = type === Sprite.Type.TILED && tileScale > 1
  node.getComponent(UITransform)?.setContentSize(new Size(
    scaled ? width / tileScale : width, scaled ? height / tileScale : height))
  node.setScale(scaled ? tileScale : 1, scaled ? tileScale : 1, 1)
  if (graphics !== null) {
    // Cocos 的 Graphics 在 enabled=false 后仍可能保留上一次生成的几何；
    // 先 clear 掉旧画面，避免它继续盖在 Sprite 上。
    graphics.clear()
    graphics.enabled = false
  }
}
