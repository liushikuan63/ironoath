/** 微信刘海、胶囊与底部手势区的共享入口；城景仍铺满视口，内容留在净区。 */
import { sys } from 'cc'
import { safeAreaInsets } from '../game/ui/DialogLayout'
import type { DialogRect, ViewportInsets } from '../game/ui/DialogLayout'

interface WindowInfo { readonly windowWidth: number; readonly windowHeight: number; readonly screenTop?: number }
interface MenuButtonRect {
  readonly left: number; readonly top: number; readonly right: number; readonly bottom: number
  readonly width: number; readonly height: number
}
interface WeChatWindowApi {
  getWindowInfo?: () => WindowInfo
  getSystemInfoSync?: () => WindowInfo
  getMenuButtonBoundingClientRect?: () => MenuButtonRect
}

/** Cocos 3.8.7 sys.getSafeAreaRect(false)已把原生边缘换成UI坐标，false保留实际非对称边缘。 */
export function viewportSafeInsets(width: number, height: number): ViewportInsets {
  const runtimeSys = sys as typeof sys & { getSafeAreaRect?: (symmetric?: boolean) => DialogRect }
  let safe = safeAreaInsets(width, height, null)
  if (sys.isBrowser) return safe
  try {
    if (typeof runtimeSys.getSafeAreaRect === 'function') safe = safeAreaInsets(width, height, runtimeSys.getSafeAreaRect(false))
  } catch { /* 旧运行时可能没有safeArea，胶囊仍需独立查询。 */ }
  const runtimeWx = (globalThis as typeof globalThis & { wx?: WeChatWindowApi }).wx
  if (typeof runtimeWx?.getMenuButtonBoundingClientRect !== 'function') return safe
  let windowInfo: WindowInfo | undefined
  try { windowInfo = runtimeWx.getWindowInfo?.() } catch { /* 兼容旧基础库。 */ }
  if (windowInfo === undefined) {
    try { windowInfo = runtimeWx.getSystemInfoSync?.() } catch { return safe }
  }
  if (windowInfo === undefined) return safe
  try {
    const rect = runtimeWx.getMenuButtonBoundingClientRect()
    const screenTop = windowInfo.screenTop ?? 0
    if (![width, height, windowInfo.windowWidth, windowInfo.windowHeight, screenTop,
      rect.left, rect.top, rect.right, rect.bottom, rect.width, rect.height].every(Number.isFinite)
      || width <= 0 || height <= 0 || windowInfo.windowWidth <= 0 || windowInfo.windowHeight <= 0
      || screenTop < 0 || rect.width <= 0 || rect.height <= 0 || rect.left < 0
      || rect.right > windowInfo.windowWidth || rect.right <= rect.left
      || rect.top < screenTop || rect.bottom <= rect.top || rect.bottom > screenTop + windowInfo.windowHeight / 2) return safe
    // 胶囊是屏幕左上原点的px；扣窗口screenTop后换算一次，不再乘DPR。8是内容留隙。
    const menuTop = (rect.bottom - screenTop) * height / windowInfo.windowHeight + 8
    return { ...safe, top: Math.max(safe.top, menuTop) }
  } catch { return safe }
}
