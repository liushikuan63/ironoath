/**
 * 职责：Cocos 节点的**世界坐标 → 浏览器屏幕像素**换算，量具共用一份。
 * 依赖：无（只在浏览器里跑，Node 侧只把它交给 `page.evaluate`）。
 *
 * <p>为什么单独一份：这个换算原先内联在 `shot-panel-sweep.mjs` 里，写的是
 * `innerWidth/2 + world.x*scale`。而 Cocos UI 的世界原点在**设计区左下角**，不是屏幕中心 ——
 * 2026-09-26 实测：设计区 960×600、视口 1440×900（scale 1.5）时，
 * `NavBar` 的 world=(480,34) 而 local=(0,-266)，即 world = local + (480,300)。
 * 那个写法把屏幕中心算成了 1440（超出视口一倍），**点击一直落空**；
 * 而调用方后面跟着"强行把 Guide 节点 `active=false`"的兜底，于是空点被兜底吃掉、量具照绿
 * （与记忆 [[tooling-probe-false-green-shapes]] 里"兜底掩盖坏读数"同一族）。
 *
 * <p>正确口径：`screenX = world.x * scaleX`、`screenY = innerHeight - world.y * scaleY`
 * （y 轴翻转：世界向上、屏幕向下）。
 *
 * <p>⚠ `page.evaluate(fn)` 只序列化这一个函数的源码，**不得闭包引用模块作用域**
 * （记忆 [[tooling-playwright-probe-patterns]]）。所以这里自带整棵树的遍历，不拆"核心 + 壳"。
 */

/**
 * 按节点名找一颗**当前可见**的节点，返回它的屏幕像素坐标。
 * @param {string} name 节点名（如 `Nav-more` / `GuideNext` / `ZoomInButton`）
 * @returns {{x:number,y:number,worldX:number,worldY:number}|null} 找不到或不可见时 null
 */
export function nodeScreenPos(name) {
  const scene = window.cc.director.getScene()
  if (scene === undefined || scene === null) return null
  let found = null
  const walk = (n) => {
    if (found !== null) return
    if (n.name === name && n.activeInHierarchy) { found = n; return }
    for (const c of n.children) walk(c)
  }
  walk(scene)
  if (found === null) return null
  const world = found.getWorldPosition()
  const visible = window.cc.view.getVisibleSize()
  return {
    x: world.x * window.cc.view.getScaleX(),
    y: window.innerHeight - world.y * window.cc.view.getScaleY(),
    worldX: world.x,
    worldY: world.y,
    /**
     * 换算假设：画布正好铺满视口（没有黑边偏移）。本仓库的量具一律固定 1440×900 = 1.5×960×600，
     * 假设成立；真机其它比例下 Cocos 的适配策略可能留黑边，那时这个换算会整体偏一个偏移量。
     * **调用方必须把它当判据的一部分**（不成立就失败），别静默拿坐标去点。
     */
    fillsViewport: window.innerWidth === Math.round(visible.width * window.cc.view.getScaleX())
      && window.innerHeight === Math.round(visible.height * window.cc.view.getScaleY()),
  }
}
