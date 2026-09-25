/**
 * 职责：像素法"底板压字"的**规划器**（浏览器侧）——算出每颗 Label 的字形带，以及
 * "DFS 次序排在它之后、且世界盒与字形带相交"的 `Graphics` 底板清单。
 * 依赖：无（只碰 `window.cc`）；被 `verify-label-fit-runtime.mjs` 与正例验证脚本共用，
 * 所以这里只有一份实现 —— 复制一份去验证等于验证另一个东西。
 *
 * <p>`page.evaluate` 只序列化这一个函数的源码，故它必须自带全部实现、不引用模块作用域。
 * 标定与两个必踩过的坑见 `verify-label-fit-runtime.mjs` 文件头与台账 #399/#400/#409。
 */
export function planPlateCoverage(panelKey) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === panelKey)
  if (!panel) return null
  const order = []
  const walkOrder = (n) => {
    order.push(n)
    for (const c of n.children) walkOrder(c)
  }
  walkOrder(panel)
  const vis = window.cc.view.getVisibleSize()
  const can = window.cc.view.getCanvasSize()
  const scale = can.width / vis.width
  const bands = []
  const plates = []
  order.forEach((n, i) => {
    const lb = n.getComponent('cc.Label')
    const str = lb ? (lb.string ?? '') : ''
    if (str.length === 0 || !n.activeInHierarchy) return
    const bb = n.getComponent('cc.UITransform').getBoundingBoxToWorld()
    let units = 0
    for (const ch of str) units += ch.charCodeAt(0) < 128 ? 0.55 : 1
    const est = Math.min(units * lb.fontSize, bb.width)
    const align = lb.horizontalAlign
    const gx0 = align === 0 ? bb.x : (align === 2 ? bb.x + bb.width - est : bb.x + (bb.width - est) / 2)
    const band = {
      x: Math.round(gx0 * scale),
      y: Math.round((vis.height - (bb.y + bb.height / 2 + lb.fontSize / 2)) * scale),
      w: Math.max(1, Math.round(est * scale)),
      h: Math.max(1, Math.round(lb.fontSize * scale)),
    }
    const bi = bands.length
    bands.push({ text: str.slice(0, 10), rect: band })
    order.forEach((m, j) => {
      if (j <= i) return
      const g = m.getComponent('cc.Graphics')
      if (g === null || g === undefined || !m.activeInHierarchy || !g.enabled) return
      const pr = m.getComponent('cc.UITransform').getBoundingBoxToWorld()
      const gy0 = bb.y + bb.height / 2 - lb.fontSize / 2
      if (pr.x >= gx0 + est || gx0 >= pr.x + pr.width) return
      if (pr.y >= gy0 + lb.fontSize || gy0 >= pr.y + pr.height) return
      let slot = plates.find((p) => p.handle === j)
      if (slot === undefined) {
        slot = { handle: j, name: m.name, bands: [] }
        plates.push(slot)
      }
      slot.bands.push(bi)
    })
  })
  window.__plateNodes = order
  return { bands, plates }
}
