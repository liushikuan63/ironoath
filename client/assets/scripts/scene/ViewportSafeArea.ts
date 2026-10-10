/** 微信刘海和底部手势区的共享入口；城景仍铺满视口，仅HUD内容避开安全边缘。 */
import { sys } from 'cc'
import { safeAreaInsets } from '../game/ui/DialogLayout'
import type { DialogRect, ViewportInsets } from '../game/ui/DialogLayout'

/** Cocos 3.8.7 sys.getSafeAreaRect(false)已把原生边缘换成UI坐标，false保留实际非对称边缘。 */
export function viewportSafeInsets(width: number, height: number): ViewportInsets {
  const runtimeSys = sys as typeof sys & { getSafeAreaRect?: (symmetric?: boolean) => DialogRect }
  if (sys.isBrowser || typeof runtimeSys.getSafeAreaRect !== 'function') {
    return safeAreaInsets(width, height, null)
  }
  try {
    return safeAreaInsets(width, height, runtimeSys.getSafeAreaRect(false))
  } catch {
    return safeAreaInsets(width, height, null)
  }
}
