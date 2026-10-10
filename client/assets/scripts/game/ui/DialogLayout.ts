/** UI净区与A档弹窗几何：内容和操作留在真实净区内，长内容交给滚动容器。 */
import { PANEL_IRON_INSET } from '../art/ArtFamilies'
import { CORNER_RADIUS } from './UiTokens'

export interface DialogRect { readonly x: number; readonly y: number; readonly width: number; readonly height: number }

export interface ViewportInsets {
  readonly left: number; readonly right: number; readonly top: number; readonly bottom: number
}

/** 输入是引擎已转换好的UI Rect（左下原点），不再乘DPR或重新换算屏幕像素。 */
export function safeAreaInsets(width: number, height: number, rect: DialogRect | null): ViewportInsets {
  const zero = { left: 0, right: 0, top: 0, bottom: 0 }
  if (![width, height].every(Number.isFinite) || width <= 0 || height <= 0 || rect === null
      || ![rect.x, rect.y, rect.width, rect.height].every(Number.isFinite)
      || rect.width <= 0 || rect.height <= 0) return zero
  const left = Math.max(0, Math.min(width, rect.x))
  const rightEdge = Math.max(0, Math.min(width, rect.x + rect.width))
  const bottom = Math.max(0, Math.min(height, rect.y))
  const topEdge = Math.max(0, Math.min(height, rect.y + rect.height))
  if (rightEdge <= left || topEdge <= bottom) return zero
  return { left, right: width - rightEdge, top: height - topEdge, bottom }
}

/** 极矮视口用 token 绘制薄边框；不把 C 档提示条拉成 A 档面板。 */
export const DIALOG_COMPACT_INSET = {
  left: CORNER_RADIUS, right: CORNER_RADIUS, top: CORNER_RADIUS, bottom: CORNER_RADIUS,
} as const

/** 按真实自身框顺序排正文；上锚条行与中心锚文字都从同一条上沿往下计账。 */
export function dialogBodyFlow(boxes: readonly {
  readonly height: number; readonly anchorY: number; readonly visible: boolean
}[]): { readonly positions: readonly number[]; readonly height: number } {
  let top = 0
  const positions = boxes.map(box => {
    if (!box.visible) return 0
    const position = -top - box.height * (1 - box.anchorY)
    top += box.height + CORNER_RADIUS
    return position
  })
  return { positions, height: Math.max(0, top - CORNER_RADIUS) }
}

export function dialogLayout(area: DialogRect, contentWidth: number, naturalHeight: number): {
  readonly width: number; readonly height: number; readonly centerY: number;
  readonly innerTop: number; readonly innerBottom: number; readonly innerWidth: number;
  readonly viewportHeight: number; readonly footerY: number;
  readonly compact: boolean;
} {
  const width = Math.min(area.width - 24, contentWidth + PANEL_IRON_INSET.left + PANEL_IRON_INSET.right)
  const availableHeight = area.height - 16
  const height = Math.min(availableHeight, naturalHeight + PANEL_IRON_INSET.top + PANEL_IRON_INSET.bottom)
  const centerY = area.y + area.height / 2
  // 薄框使自然短窗也会低于240；是否换矮屏兜底取决于真实净区，而非正文长短。
  const compact = availableHeight < 240
  const inset = compact ? DIALOG_COMPACT_INSET : PANEL_IRON_INSET
  const innerTop = centerY + height / 2 - inset.top
  const innerBottom = centerY - height / 2 + inset.bottom
  return { width, height, centerY, innerTop, innerBottom,
    compact, innerWidth: width - inset.left - inset.right,
    viewportHeight: Math.max(1, innerTop - innerBottom - 52), footerY: innerBottom + 22 }
}
