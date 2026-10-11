/** 引导提示的响应式几何；只按当前面板和可用区排版，不读步骤id或完成判定。 */
import type { DialogRect } from './DialogLayout'

export interface GuideBubbleLayout {
  readonly width: number; readonly height: number; readonly x: number; readonly y: number
  readonly inset: number; readonly innerWidth: number; readonly buttonWidth: number
}

export function guideBubbleLayout(area: DialogRect, viewportWidth: number, viewportHeight: number,
                                  panelKey: string | null, preferred: {
  readonly width: number; readonly height: number; readonly margin: number; readonly buttonWidth: number
}): GuideBubbleLayout {
  // 600/640高沿用既有城景；紧凑横屏只收city提示，其他面板的长文保留原宽。
  const compactCity = panelKey === 'city' && viewportHeight < 600 && viewportWidth > viewportHeight
  const width = Math.min(viewportWidth - preferred.margin * 2, area.width - 24,
    compactCity ? 324 : preferred.width)
  const inset = compactCity ? 12 : 16
  const height = preferred.height
  const top = Math.min(area.y + area.height, viewportHeight / 2)
  // 任务领取、军队训练/加速、社交创建都在右列；提示让开操作列。
  // 选择窗打开时由显式可见性回调暂收引导，输入焦点不参与引导条件。
  const left = panelKey === 'quest' || panelKey === 'army' || panelKey === 'social'
  const x = left ? area.x + 12 + width / 2 : area.x + area.width - 12 - width / 2
  // 地图顶部是缩放/行军工具栏；军队和社交顶部是页签与入口，提示贴安全底角。
  const bottom = panelKey === 'world' || panelKey === 'army' || panelKey === 'social'
  const y = bottom ? area.y + height / 2 + 8
    : Math.max(top - height / 2 - 12, area.y + height / 2 + 8)
  return { width, height, x, y, inset, innerWidth: width - inset * 2,
    buttonWidth: compactCity ? 144 : preferred.buttonWidth }
}
