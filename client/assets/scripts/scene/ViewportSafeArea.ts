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
  onWindowResize?: (listener: () => void) => void
  onShow?: (listener: () => void) => void
}

let cached: { width: number; height: number; api: WeChatWindowApi | undefined;
  insets: ViewportInsets; validUntil: number } | null = null
const observedApis = new WeakSet<WeChatWindowApi>()
function invalidateInsets(): void { cached = null }

/** 稳定窗口的净区共享一次读取；原生同步查询在微信工具里会生成带完整调用栈的桥接请求。 */
export function viewportSafeInsets(width: number, height: number): ViewportInsets {
  if (sys.isBrowser) return safeAreaInsets(width, height, null)
  const api = (globalThis as typeof globalThis & { wx?: WeChatWindowApi }).wx
  if (api !== undefined && !observedApis.has(api)) {
    observedApis.add(api)
    try { api.onWindowResize?.(invalidateInsets) } catch { /* 旧运行时可能没有窗口监听。 */ }
    try { api.onShow?.(invalidateInsets) } catch { /* 前台监听与业务前台回调相互独立。 */ }
  }
  const now = Date.now()
  if (cached !== null && cached.width === width && cached.height === height
      && cached.api === api && now < cached.validUntil) return { ...cached.insets }
  let retryNeeded = false
  const insets = measureSafeInsets(width, height, api, () => { retryNeeded = true })
  cached = { width, height, api, insets, validUntil: retryNeeded ? now + 1000 : Infinity }
  return { ...insets }
}

/** Cocos 3.8.7 sys.getSafeAreaRect(false)已把原生边缘换成UI坐标，false保留实际非对称边缘。 */
function measureSafeInsets(width: number, height: number, runtimeWx: WeChatWindowApi | undefined,
  retry: () => void): ViewportInsets {
  const runtimeSys = sys as typeof sys & { getSafeAreaRect?: (symmetric?: boolean) => DialogRect }
  let safe = safeAreaInsets(width, height, null)
  try {
    if (typeof runtimeSys.getSafeAreaRect === 'function') {
      const rect = runtimeSys.getSafeAreaRect(false)
      safe = safeAreaInsets(width, height, rect)
      if (rect == null || ![rect.x, rect.y, rect.width, rect.height].every(Number.isFinite)
          || rect.width <= 0 || rect.height <= 0) retry()
    }
  } catch { retry() /* 旧运行时可能没有safeArea，胶囊仍需独立查询。 */ }
  if (typeof runtimeWx?.getMenuButtonBoundingClientRect !== 'function') return safe
  let windowInfo: WindowInfo | undefined
  try { windowInfo = runtimeWx.getWindowInfo?.() } catch { /* 兼容旧基础库。 */ }
  if (windowInfo === undefined) {
    try { windowInfo = runtimeWx.getSystemInfoSync?.() } catch { retry(); return safe }
  }
  if (windowInfo === undefined) { retry(); return safe }
  try {
    const rect = runtimeWx.getMenuButtonBoundingClientRect()
    const screenTop = windowInfo.screenTop ?? 0
    if (![width, height, windowInfo.windowWidth, windowInfo.windowHeight, screenTop,
      rect.left, rect.top, rect.right, rect.bottom, rect.width, rect.height].every(Number.isFinite)
      || width <= 0 || height <= 0 || windowInfo.windowWidth <= 0 || windowInfo.windowHeight <= 0
      || screenTop < 0 || rect.width <= 0 || rect.height <= 0 || rect.left < 0
      || rect.right > windowInfo.windowWidth || rect.right <= rect.left
      || rect.top < screenTop || rect.bottom <= rect.top || rect.bottom > screenTop + windowInfo.windowHeight / 2) {
      retry(); return safe
    }
    // 胶囊是屏幕左上原点的px；扣窗口screenTop后换算一次，不再乘DPR。8是内容留隙。
    const menuTop = (rect.bottom - screenTop) * height / windowInfo.windowHeight + 8
    return { ...safe, top: Math.max(safe.top, menuTop) }
  } catch { retry(); return safe }
}
