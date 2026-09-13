/**
 * 职责：运行期美术资源目录 —— 统一加载 `resources/ui/generated/**` 下的 SpriteFrame。
 * 依赖：cc（resources / Sprite / SpriteFrame / UITransform）。
 *
 * <p>这层存在的意义是把「资源路径、九宫格边界、加载失败兜底」收在一个地方。
 * 面板代码只写 `applySlicedSprite(node, 'ui.panel.kingdom', ...)`，
 * 不直接拼 `resources.load` 路径，也不自己判断该用普通图还是九宫格。
 */

import {
  Graphics, JsonAsset, Node, Rect, resources, Size, Sprite, SpriteFrame, Texture2D,
  UITransform, Vec2,
} from 'cc'

type StaticArtKey =
  | 'ui.panel.kingdom'
  | 'ui.button.command'
  | 'ui.button.command.hover'
  | 'ui.button.command.pressed'
  | 'ui.button.command.disabled'
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
export type CommandButtonState = 'normal' | 'hover' | 'pressed' | 'disabled'

interface ArtSpec {
  readonly path: string
  readonly insets?: readonly [left: number, top: number, right: number, bottom: number]
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
    insets: [51, 47, 51, 47],
  },
  'ui.button.command': {
    path: 'ui/generated/ui/button-command-v1',
    insets: [54, 40, 54, 40],
  },
  'ui.button.command.hover': {
    path: 'ui/generated/ui/button-command-v1-hover',
    insets: [54, 40, 54, 40],
  },
  'ui.button.command.pressed': {
    path: 'ui/generated/ui/button-command-v1-pressed',
    insets: [54, 40, 54, 40],
  },
  'ui.button.command.disabled': {
    path: 'ui/generated/ui/button-command-v1-disabled',
    insets: [54, 40, 54, 40],
  },
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
    if (spec.insets !== undefined) {
      frame.insetLeft = spec.insets[0]
      frame.insetTop = spec.insets[1]
      frame.insetRight = spec.insets[2]
      frame.insetBottom = spec.insets[3]
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

export function commandButtonArtKey(state: CommandButtonState = 'normal'): ArtKey {
  switch (state) {
    case 'hover':
      return 'ui.button.command.hover'
    case 'pressed':
      return 'ui.button.command.pressed'
    case 'disabled':
      return 'ui.button.command.disabled'
    default:
      return 'ui.button.command'
  }
}

/** 用九宫格 Sprite 替换节点背景；加载失败时返回 false，调用方继续走 Graphics 兜底。 */
export function applySlicedSprite(node: Node, key: ArtKey, width: number, height: number): boolean {
  return applySprite(node, key, Sprite.Type.SLICED, width, height)
}

/** 用 TILED Sprite 铺满节点；地图地面用它。 */
export function applyTiledSprite(node: Node, key: ArtKey, width: number, height: number): boolean {
  return applySprite(node, key, Sprite.Type.TILED, width, height)
}

/** 用 SIMPLE Sprite 替换节点画面；地图实体与行军图标用它。 */
export function applySimpleSprite(node: Node, key: ArtKey, width: number, height: number): boolean {
  return applySprite(node, key, Sprite.Type.SIMPLE, width, height)
}

export function applyIconSprite(node: Node, key: IconArtKey, width: number, height: number): boolean {
  return applySimpleSprite(node, key, width, height)
}

export function applyTerrainSprite(node: Node, index: number,
                                   width: number, height: number): boolean {
  return applyTiledSprite(node, terrainArtKey(index), width, height)
}

export function applyCommandButton(node: Node, state: CommandButtonState,
                                   width: number, height: number): boolean {
  // 按钮母版是 512×191、带圆角描边的九宫格，游戏里却要铺到 26~34px 高；
  // 按 SIMPLE 直接压扁会把四角拉成椭圆。九宫格只拉伸中间，四角保持原比例。
  return applySlicedSprite(node, commandButtonArtKey(state), width, height)
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
                     width: number, height: number): boolean {
  const frame = artFrame(key)
  const graphics = node.getComponent(Graphics)
  if (frame === null) {
    clearSprite(node)
    return false
  }
  const sprite = node.getComponent(Sprite) ?? node.addComponent(Sprite)
  sprite.spriteFrame = frame
  sprite.type = type
  sprite.sizeMode = Sprite.SizeMode.CUSTOM
  sprite.enabled = true
  node.getComponent(UITransform)?.setContentSize(new Size(width, height))
  if (graphics !== null) {
    // Cocos 的 Graphics 在 enabled=false 后仍可能保留上一次生成的几何；
    // 先 clear 掉旧画面，避免它继续盖在 Sprite 上。
    graphics.clear()
    graphics.enabled = false
  }
  return true
}
