/**
 * 职责：世界地图"一格等于多少像素"的换算 —— 纯算术，引擎无关（B00 铁律 2）。
 * 依赖：无。
 *
 * <p><b>为什么单独成一个模块</b>：这件事原来是 `scene/WorldMap.ts` 里的一行常量
 * `CELL_PIXELS_BY_ZOOM = [8, 24]`。8 是按"3 块 × 32 格 × 8px = 768px ≈ 设计分辨率宽度"倒推的，
 * 可那是**某一个设计宽度**下的巧合：在 1440×900 的画布上 3×3 块只铺到约 53%，
 * 屏幕四周是一圈纯黑 —— 实测截图里地图只占中间约 350×350，玩家第一眼看到的世界是"半屏空黑 + 一小块地形"。
 * 换算一旦要跟着视口走，它就必须能被算、被断言，而不是埋在 `cc` 绑定的视图里靠眼睛验收。
 *
 * <p>客户端手里永远只有 9 个块（B07 红线：绝不一次性下发整张地图），
 * 所以"世界档"的含义不是"看见全世界"，而是"看见自己这 9 块的全貌"。
 */

/** 一屏摆得下几块（自己这一块的 3×3 视野）。 */
export const CHUNKS_PER_SIDE_ON_SCREEN = 3

/**
 * 各缩放档位相对"3×3 块正好铺满一屏"的倍率。
 * 档位 1 放大 3 倍 ⇒ 一屏约一块，便于点选具体格子。
 * 档位 2 是城内场景、不画地图，取到时按最大档钳，避免除零与空画面。
 */
export const CELL_ZOOM_SCALES: readonly number[] = [1, 3]

/**
 * 铺满一屏时一格的像素边长。
 *
 * <p>取 `max(宽, 高)` 而不是 `min`：地图是居中的方形视野，用 min 会让长的那一侧留黑边。
 * 宁可短的一侧溢出（可以拖）也不留黑 —— 黑边读起来像"地图到此为止"。
 *
 * <p>`chunkSize` 兜底到 ≥1：它是服务端布局下发的值，为 0 时这里会算出 Infinity，
 * 而 Infinity 传进 `setPosition` 只会得到一张空白地图（比报错更难查）。
 */
export function baseCellPixels(
  visibleWidth: number, visibleHeight: number, chunkSize: number,
): number {
  return Math.max(visibleWidth, visibleHeight)
    / (CHUNKS_PER_SIDE_ON_SCREEN * Math.max(1, chunkSize))
}

/** 某缩放档位下一格的像素边长。 */
export function cellPixels(zoom: number, baseCell: number): number {
  const index = Math.min(Math.max(zoom, 0), CELL_ZOOM_SCALES.length - 1)
  // noUncheckedIndexedAccess 让下标访问变成 number|undefined；常量表长度固定，兜底值只为类型收窄
  return baseCell * (CELL_ZOOM_SCALES[index] ?? CELL_ZOOM_SCALES[0] ?? 1)
}
