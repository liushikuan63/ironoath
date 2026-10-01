/**
 * 职责：把「点某个 Cocos 节点」收进一个出处 —— 点击坐标由 **Cocos 自己的输入命中**决定，
 * 而不是本仓库手算一套坐标公式（#624 / #626 / #632 / #633）。
 * 依赖：无（浏览器侧只碰 `window.cc` / `document`；Node 侧只碰 playwright 的 `page`）。
 *
 * <p><b>为什么必须交给引擎判</b>：Cocos 派发触摸**之前先过节点自己的 hitTest**，没过就不派发
 * —— 引擎 `cocos/scene-graph/node-event-processor.ts:508`（产物 `cc.js` 压缩成
 * `hitTest(ly,t.windowId)` 出现在 `_handleTouchStart` 的派发门里）。而 `hitTest` 吃的是
 * `EventMouse.getLocation()` 的坐标，也就是引擎 `pal/input/web/mouse-input.ts:82-90` 算出来的那个值：
 * <pre>
 *   ex = (clientX - canvasRect.x) * dpr
 *   ey = (canvasRect.y + canvasRect.height - clientY) * dpr
 * </pre>
 * **只乘 dpr**：不减 viewport 原点、不除 `getScaleX()`、不按 `getVisibleSizeInPixel()` 归一化。
 * 本仓库原来的公式除的却是 `getVisibleSizeInPixel()`（一个**设计空间**尺寸）⇒ 分母整个换了，
 * 算出来的点压根不是引擎认的那个。#610 的「偏 12px」、#616 的「base 在 33/281 之间跳」、
 * #618 反复更正偏移的用途、#624 的往返不一致，全是这一处的下游症状。
 *
 * <p><b>口径（三条自检全过才算数）</b>：
 *   ① 往返：`camera.worldToScreen(out, worldPos)` 之后 `camera.screenToWorld(out2, sp)` 必须回到原世界点
 *      —— 这一条与 CSS 换算、与参数顺序都无关，是**独立**的量具自检（#624 缺的正是它）；
 *   ② 引擎自命中：`box.hitTest(Vec2(sp), cam.systemWindowId)` 必须为真（与输入系统同一个函数）；
 *   ③ CSS 点必须落在 canvas 矩形内 —— 落在外面的话事件根本到不了 canvas 的监听器。
 * 任一条不过就返回 `verified:false` + reason。**调用方拿到 false 必须停止**并报「点击坐标不可信」，
 * 不得继续拿红绿当功能判据 —— 这就是 #624 留下的那句话。
 *
 * <p><b>为什么不拆「纯核心 + 浏览器壳」两份</b>：`page.evaluate(fn)` 只序列化这一个函数的源码，
 * 模块作用域里的其它绑定在浏览器里不存在（同 `tools/lib/guide-overlay.mjs` 的理由）。
 * 所以自检给 `globalThis.window` / `globalThis.document` 装一棵假引擎树，直接调同一个实现。
 */

/**
 * 浏览器侧：算出「点这个节点」的 CSS 坐标，并用引擎自己的命中测试作背书。
 * @param {object} opts
 * @param {string} [opts.name] 节点名精确匹配
 * @param {string} [opts.labelPrefix] 按 `cc.Label` 文本前缀匹配（不写死行列号）
 * @param {string} [opts.within] 把搜索范围限制在这一个节点的子树里
 * @param {Array<{label:string,x:number,y:number}>} [opts.compare] 附加的对照点（CSS 坐标），
 *        逐个报出「引擎认为它落在哪个节点上」—— 用来把旧公式与新公式放在同一份读数下对打。
 * @returns {{verified:boolean, reason:string|null, node:string|null, x:number|null, y:number|null,
 *            engine:object|null, dpr:number|null, camera:string|null, attempts:Array, compare:Array}}
 */
export function resolveCocosClickPoint(opts) {
  const o = opts ?? {}
  const fail = (reason, extra) =>
    Object.assign({ verified: false, reason, node: null, x: null, y: null, engine: null, dpr: null, camera: null }, extra ?? {})

  const cc = window.cc
  const scene = cc && cc.director ? cc.director.getScene() : null
  if (scene == null) return fail('no-scene')

  // ---- 1. 找节点。深度上限 64 是 #624 记下的坑：场景树里有过很深的链，不设上限会打爆栈。
  // `children` 读成 `?? []`：遍历的是别人的场景树，不能假设每一层都带 children。
  const kids = (n) => n.children ?? []
  const findByName = (root, name) => {
    let found = null
    const walk = (n, depth) => {
      if (found !== null || n == null || depth > 64) return
      if (n.name === name) { found = n; return }
      for (const c of kids(n)) walk(c, depth + 1)
    }
    walk(root, 0)
    return found
  }
  const findByLabel = (root, prefix) => {
    let found = null
    const walk = (n, depth) => {
      if (found !== null || n == null || depth > 64) return
      const lab = n.getComponent ? n.getComponent('cc.Label') : null
      if (lab !== null && lab !== undefined && String(lab.string ?? '').startsWith(prefix)) { found = n; return }
      for (const c of kids(n)) walk(c, depth + 1)
    }
    walk(root, 0)
    return found
  }

  let scope = scene
  if (o.within != null) {
    const host = findByName(scene, o.within)
    if (host == null) return fail('within-not-found', { node: o.within })
    scope = host
  }
  let node = null
  if (o.name != null) node = findByName(scope, o.name)
  else if (o.labelPrefix != null) node = findByLabel(scope, o.labelPrefix)
  else return fail('no-criterion', {})
  if (node === null) return fail('node-not-found', { node: (o.name ?? o.labelPrefix ?? null) })
  if (node.activeInHierarchy !== true) return fail('node-inactive', { node: node.name })

  const box = node.getComponent ? node.getComponent('cc.UITransform') : null
  if (box == null || box === undefined) return fail('no-uitransform', { node: node.name })

  // ---- 2. 读画布几何。dpr 用引擎自己的口径（`screenAdapter.devicePixelRatio` 在 Web 上就是它）。
  const canvas = document.querySelector('canvas')
  if (canvas == null) return fail('no-canvas', { node: node.name })
  const rect = canvas.getBoundingClientRect()
  const dpr = window.devicePixelRatio || 1
  if (!(rect.width > 0) || !(rect.height > 0)) return fail('zero-canvas', { node: node.name })

  // CSS 像素 → 引擎点：`_getLocation` 的原式（mouse-input.ts:82-90），只乘 dpr、y 从下往上。
  const toEngine = (cssX, cssY) => ({ x: (cssX - rect.left) * dpr, y: (rect.top + rect.height - cssY) * dpr })
  const toCss = (ex, ey) => ({ x: rect.left + ex / dpr, y: rect.top + rect.height - ey / dpr })
  // Web 上只有一个系统窗口，输入系统给 hitTest 的 windowId 就是 0（见下面第 4 步的注释）。
  const WEB_WINDOW_ID = 0
  const insideCanvas = (css) =>
    css.x >= rect.left && css.x <= rect.left + rect.width && css.y >= rect.top && css.y <= rect.top + rect.height

  // ---- 3. 收相机。公开 API 里没有 `getRenderScene()`（只有引擎私有的 `_getRenderScene`），
  //        所以从场景树收集 cc.Camera 组件 —— 输入系统认的也是这台相机的系统窗口。
  const cameras = []
  {
    const seen = []
    const walk = (n, depth) => {
      if (n == null || depth > 64) return
      const cam = n.getComponent ? n.getComponent('cc.Camera') : null
      if (cam !== null && cam !== undefined && seen.indexOf(cam) === -1) { seen.push(cam); cameras.push(cam) }
      for (const c of kids(n)) walk(c, depth + 1)
    }
    walk(scene, 0)
  }
  if (cameras.length === 0) return fail('no-camera', { node: node.name })

  // ---- 4. 节点世界坐标 → 逐台相机求屏幕点 → 三条自检。
  //
  // ⚠️ **锚点补偿**（收口清单 #623 / #640）：`convertToWorldSpaceAR(Vec3(0,0,0))` 求的是
  // **节点局部坐标系的原点**，而原点落在**锚点**上，**不一定是盒子中心**。
  // 资源条格子就是这么写的 —— `addLabel` 里 `if (leftAligned) transform.setAnchorPoint(0, 0.5)`，
  // 于是 `(0,0,0)` 是**左边缘中点**。盒宽 155 世界单位（屏幕 232px）⇒ 偏出去约 116px，
  // 落在格子之外，点过去什么也不发生。
  // ⇒ 这里先按 `anchorPoint` 算出「盒中心相对锚点」的局部偏移再换算。
  // ⚠️ 这正是 #633 在探针里内联做对、而本模块最初漏掉的那一处；两版曾并存（重复实现），
  // 现在探针改接本模块，所以**修在这里**，探针侧不再各算一套。
  const boxSize = box.contentSize
  const anchor = box.anchorPoint
  const world = box.convertToWorldSpaceAR(new cc.Vec3(
    boxSize.width * (0.5 - anchor.x),
    boxSize.height * (0.5 - anchor.y),
    0))
  const attempts = []
  let roundTripFailed = false
  let hitTestFailed = false
  let outside = false
  for (const cam of cameras) {
    // **一参、就地改写**（#639）：产物里 Camera 这两个方法的实参序与 .d.ts 写的**相反** ——
    // 引擎自己的包装 `this._camera.worldToScreen(e, t)` 里 `e` 是世界点、`t` 是输出。
    // 实测（本仓 3.8.7 产物，1440×900 视口）：`worldToScreen(v)` 把 v 就地改成 (150,75) 是对的；
    // 而 `worldToScreen(new Vec3(), world)` 会把 `world` 写成 (0,0) —— 第一个参数被当成了世界点。
    // 一参形式对两种实参序都成立，所以用它，不去赌签名（#638 曾按 .d.ts 用两参，整轮读数全废）。
    const sp = cam.worldToScreen(new cc.Vec3(world.x, world.y, world.z))
    const back = cam.screenToWorld(new cc.Vec3(sp.x, sp.y, sp.z))
    const drift = Math.max(Math.abs(back.x - world.x), Math.abs(back.y - world.y))
    // `windowId` 传 **0**，与 Web 输入系统一致（`EventMouse.windowId` 默认 0，产物
    // `i.hitTest=function(t,e){void 0===e&&(e=0); …}`）。⚠️ **不能传 `cam.systemWindowId`**：
    // `hitTest` 内部遍历的是**渲染场景**里那台相机（`_getRenderScene().cameras`），而组件
    // `cc.Camera` 上的 `systemWindowId` 实测是 `undefined` ⇒ `undefined !== 0`
    // ⇒ 那台被 `continue` 掉、hitTest 恒 false，看上去就成了"点不中"。这是 #638 首次实跑的真实拦因。
    const accepted = box.hitTest(new cc.Vec2(sp.x, sp.y), WEB_WINDOW_ID) === true
    const css = toCss(sp.x, sp.y)
    const inCanvas = insideCanvas(css)
    const row = {
      camera: (cam.node && cam.node.name) || '(anon)',
      componentSystemWindowId: cam.systemWindowId,
      engine: { x: sp.x, y: sp.y },
      css: { x: css.x, y: css.y },
      roundTripDrift: drift,
      roundTripOk: drift <= 0.5,
      hitTest: accepted,
      insideCanvas: inCanvas,
    }
    attempts.push(row)
    if (!row.roundTripOk) { roundTripFailed = true; continue }
    if (!accepted) { hitTestFailed = true; continue }
    if (!inCanvas) { outside = true; continue }
    const verdict = {
      verified: true, reason: null, node: node.name,
      x: css.x, y: css.y, engine: row.engine, dpr, camera: row.camera, attempts, compare: [],
    }
    verdict.compare = judgeCompare(cc, box, toEngine, cameras, o.compare)
    return verdict
  }

  // 全挂了：reason 按**最先被卡住的那一条**给，别把往返失败说成"点不中"。
  const reason = roundTripFailed ? 'roundtrip-failed'
    : hitTestFailed ? 'hittest-rejected'
      : outside ? 'outside-canvas' : 'no-camera-accepted'
  return fail(reason, { node: node.name, dpr, attempts, compare: judgeCompare(cc, box, toEngine, cameras, o.compare) })

  // 对照点：同一批读数下，引擎认为这些 CSS 点各自落在谁身上。旧公式的点会在这里被判负。
  function judgeCompare(ccx, boxx, convert, camList, list) {
    const rows = []
    for (const item of list ?? []) {
      const e = convert(item.x, item.y)
      const row = { label: item.label, css: { x: item.x, y: item.y }, engine: e, insideCanvas: insideCanvas({ x: item.x, y: item.y }) }
      for (const cam of camList) {
        row[cam.node && cam.node.name ? cam.node.name : '(anon)'] = boxx.hitTest(new ccx.Vec2(e.x, e.y), WEB_WINDOW_ID) === true
      }
      rows.push(row)
    }
    return rows
  }
}

/**
 * Node 侧：解析 → 门禁 → 真点。
 * `verified !== true` 时**一个像素都不点**（调用方据此把"红绿"降级成"坐标不可信"）。
 * @returns {Promise<object>} 解析结果 + `clicked:boolean`
 */
export async function clickNodeViaCocos(page, opts = {}) {
  const point = await page.evaluate(resolveCocosClickPoint, opts)
  if (point.verified !== true) return Object.assign({ clicked: false }, point)
  await page.mouse.click(point.x, point.y)
  return Object.assign({ clicked: true }, point)
}
