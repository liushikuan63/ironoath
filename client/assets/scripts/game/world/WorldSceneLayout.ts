/** 世界地图的显示净区与投影。只处理像素，服务端格坐标、探索状态及缩放档位照旧。 */
import type { CaptionViewport } from './WorldLabels'

export const WORLD_NAV_INSET = 60
export const WORLD_BUTTON_HEIGHT = 34
const OUTER = 12
const GAP = 8
const ROW_PITCH = 42
const STATUS_HEIGHT = 30

const BUTTONS = [
  ['ZoomInButton', 64], ['ZoomOutButton', 64], ['HomeButton', 64],
  ['ExileButton', 180], ['MarchButton', 120],
] as const

export interface WorldToolbarButton {
  readonly name: typeof BUTTONS[number][0]
  readonly x: number
  readonly y: number
  readonly width: number
}

export interface WorldSceneLayout {
  readonly width: number
  readonly height: number
  readonly hudHeight: number
  readonly statusY: number
  readonly mapHeight: number
  readonly mapCenterY: number
  readonly mapBottom: number
  readonly mapTop: number
  readonly buttons: readonly WorldToolbarButton[]
}

/** 操作条按可见宽度换行；状态行在按钮下方，避免坐标与迁城冷却互相覆盖。 */
export function worldSceneLayout(width: number, height: number): WorldSceneLayout {
  const buttons: WorldToolbarButton[] = []
  let left = OUTER
  let row = 0
  for (const [name, requestedWidth] of BUTTONS) {
    const buttonWidth = Math.min(requestedWidth, Math.max(1, width - OUTER * 2))
    if (left > OUTER && left + buttonWidth > width - OUTER) {
      row += 1
      left = OUTER
    }
    buttons.push({ name, width: buttonWidth,
      x: -width / 2 + left + buttonWidth / 2,
      y: height / 2 - OUTER - row * ROW_PITCH - WORLD_BUTTON_HEIGHT / 2 })
    left += buttonWidth + GAP
  }
  const hudHeight = OUTER + (row + 1) * ROW_PITCH + STATUS_HEIGHT
  const mapBottom = -height / 2 + WORLD_NAV_INSET
  const mapTop = height / 2 - hudHeight
  return {
    width, height, hudHeight, buttons,
    statusY: height / 2 - hudHeight + STATUS_HEIGHT / 2,
    mapBottom, mapTop,
    mapHeight: Math.max(1, mapTop - mapBottom),
    mapCenterY: (mapBottom + mapTop) / 2,
  }
}

/** 世界格中心在地图净区中居中；点击仍走引擎对这同一个 MapLayer 的逆变换。 */
export function worldMapTranslation(
  layout: WorldSceneLayout, cameraX: number, cameraY: number, cell: number,
): { x: number; y: number } {
  return { x: -(cameraX + 0.5) * cell,
    y: layout.mapCenterY - (cameraY + 0.5) * cell }
}

/** 名牌裁剪是同一平移的逆变换，避免相机改了、标签净区仍按旧屏幕中心算。 */
export function worldCaptionViewport(
  layout: WorldSceneLayout, cameraX: number, cameraY: number, cell: number,
): CaptionViewport {
  const translation = worldMapTranslation(layout, cameraX, cameraY, cell)
  return {
    minX: -layout.width / 2 - translation.x,
    maxX: layout.width / 2 - translation.x,
    minY: layout.mapBottom - translation.y,
    maxY: layout.mapTop - translation.y,
  }
}

/** UI 坐标原点在左下方。顶部操作条和底部导航均不启动地图手势。 */
export function worldMapInputAllowed(layout: WorldSceneLayout, uiY: number): boolean {
  return uiY >= WORLD_NAV_INSET && uiY < layout.height - layout.hudHeight
}

/** 实际裁帧按一块至多 2×2 个纹样铺地，随投影放大；不改变地貌 variant 或世界坐标。 */
export function worldTerrainTileScale(chunkPixels: number, framePixels: number): number {
  return Math.max(1, chunkPixels / (Math.max(1, framePixels) * 2))
}

export interface WorldFogContour {
  readonly points: readonly { readonly x: number; readonly y: number }[]
  readonly opacity: number
}

/**
 * 同一视口只画四组缓淡雾纹，不以 chunk 或格子重复。
 * 不透明底层由视图先铺满；这些固定低密度轮廓不读取任何世界数据，也不随时间动画。
 */
export function worldFogContours(width: number, height: number): readonly WorldFogContour[] {
  const clouds = [
    [-0.36, 0.21, 0.44, 0.30, 0.4], [0.18, -0.31, 0.52, 0.32, 1.9],
    [0.47, 0.26, 0.38, 0.34, 3.6], [-0.45, -0.44, 0.31, 0.24, 5.1],
  ] as const
  const contours: WorldFogContour[] = []
  for (const [x, y, rx, ry, phase] of clouds) {
    for (let band = 0; band < 6; band++) {
      const shrink = 1 - band * 0.09
      const points = []
      for (let point = 0; point < 32; point++) {
        const angle = point * Math.PI * 2 / 32
        const irregularity = 1 + 0.13 * Math.sin(angle * 3 + phase)
          + 0.07 * Math.cos(angle * 5 - phase)
        points.push({ x: width * (x + Math.cos(angle) * rx * shrink * irregularity),
          y: height * (y + Math.sin(angle) * ry * shrink * irregularity) })
      }
      contours.push({ points, opacity: 8 })
    }
  }
  return contours
}
