/** A 档弹窗几何：把内容与操作留在真实净区内，长内容交给滚动容器。 */
import { PANEL_IRON_INSET } from '../art/ArtFamilies'
import { CORNER_RADIUS } from './UiTokens'

export interface DialogRect { readonly x: number; readonly y: number; readonly width: number; readonly height: number }

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
  const height = Math.min(area.height - 16, naturalHeight + PANEL_IRON_INSET.top + PANEL_IRON_INSET.bottom)
  const centerY = area.y + area.height / 2
  const compact = height < 240
  const inset = compact ? DIALOG_COMPACT_INSET : PANEL_IRON_INSET
  const innerTop = centerY + height / 2 - inset.top
  const innerBottom = centerY - height / 2 + inset.bottom
  return { width, height, centerY, innerTop, innerBottom,
    compact, innerWidth: width - inset.left - inset.right,
    viewportHeight: Math.max(1, innerTop - innerBottom - 52), footerY: innerBottom + 22 }
}
