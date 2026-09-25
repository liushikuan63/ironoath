/**
 * 职责：Cocos 面板里"点得动某个节点"的两个页内助手，量具共用一份。
 * 依赖：只在浏览器里跑（`page.evaluate` 的回调），因此**不得闭包引用模块作用域**——
 *       序列化过去只有函数源码，闭包里的变量会 undefined（这条坑见记忆 `tooling-playwright-probe-patterns`）。
 *
 * <p>为什么单独一份：横扫 `tools/verify-label-fit-runtime.mjs` 与植入正例
 * `tools/verify-plate-plant.mjs` 都要切页签，而后者从前**内联抄了一份**（同一个 DFS + 同一次 emit）。
 * 两份的失效方式不一样：改壳名 / 改锚点时只会红其中一份，另一份安静地"没点到也算过"（台账 #424）。
 */

/**
 * 切到一个页签。两个面板的处理器签名都是 `(_event: EventTouch) => ...`（不读那个参数），
 * 所以按节点名找到壳直接 `emit` 就能换页 —— 换页签是纯客户端动作，不发写请求
 * （与"覆盖层要点掉就别点"那条相反：#391 那块一点就发引导推进，这一族点了什么都不发）。
 *
 * <p>⚠ 这**绕过了命中测试**：量的是"切过去之后那一屏的排版"，不是"页签点不点得动"。
 * 后者要真鼠标坐标 + 画布缩放换算，是另一格的活，别把这里的绿读成"页签一定能按"。
 */
export function clickTabNode(name) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let found = null
  const find = (n) => {
    if (found !== null) return
    if (n.name === name && n.activeInHierarchy) {
      found = n
      return
    }
    for (const c of n.children) find(c)
  }
  for (const panel of game?.children ?? []) find(panel)
  if (found === null) return false
  found.emit('touch-start', null)
  return true
}

/**
 * 按**按钮上那句文本**点行内的动作按钮（翻页那一族：`ActionButton` / `2` / `3` 是运行时造的壳，
 * 没有 `cc.Button`，命中测试走不通，只能照页签那样 `emit('touch-start')`）。
 * 从 Label 往上爬到壳，是因为文本挂在壳的子节点上。
 */
export function clickRowAction(text) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const find = (n) => {
    if (hit !== null) return
    const lb = n.getComponent('cc.Label')
    if (lb && lb.string === text && n.activeInHierarchy) {
      for (let p = n; p !== null; p = p.parent) {
        if (/^ActionButton/.test(p.name)) {
          if (p.activeInHierarchy) hit = p
          break
        }
      }
      if (hit !== null) return
    }
    for (const c of n.children) find(c)
  }
  for (const panel of game?.children ?? []) find(panel)
  if (hit === null) return false
  hit.emit('touch-start', null)
  return true
}
