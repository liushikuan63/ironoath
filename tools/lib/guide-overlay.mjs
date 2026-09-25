/**
 * 职责：把"藏掉新手引导那块板"这一件事收进一个出处，给所有会截图的运行时量具共用
 * （原先只有 `verify-label-fit-runtime.mjs` 有，其余 26 份量具的截图里被引导盖住那一屏没人发现，#388/#391）。
 * 依赖：无（浏览器侧只碰 `window.cc`；Node 侧只碰 playwright 的 `page`）。
 *
 * <p><b>为什么是"藏"不是"点掉"</b>：`GuideNext` 的 `touch-start` 会发一次推进引导的**写请求**，
 * 而 dev 新号那一步的前置没满足 ⇒ 写失败，屏幕上换成"网络不稳定，正在重试（第 1 次）"，
 * 板子还在、还多一条重试提示（#391 实测）。引导是玩家可关的**覆盖层**，藏掉它不改被量那一屏的几何。
 *
 * <p><b>为什么 `hideGuideBoard` 必须自带全部实现</b>：`page.evaluate(fn)` 只序列化这一个函数的源码，
 * 模块作用域里的其它绑定在浏览器里不存在（记忆 [[tooling-playwright-probe-patterns]]）。
 * 所以这里不拆"纯核心 + 浏览器壳"两份 —— 那正是本仓库一直在删的"第二实现"；
 * 改为让自检直接给 `globalThis.window` 装一棵假场景树来调同一个函数。
 */

/**
 * 浏览器侧：找到引导自己那一层（`Game` 或 `Canvas` 的直接子节点）并藏掉。
 * @returns {boolean} 是否真的藏掉了一块板（false = 这一屏本来就没有引导，或被拒绝动根节点）
 */
export function hideGuideBoard() {
  const scene = window.cc.director.getScene()
  if (scene === undefined || scene === null) return false
  const found = []
  const find = (n) => {
    if (!n.activeInHierarchy) return
    if (n.name === 'GuideNext') found.push(n)
    for (const c of n.children) find(c)
  }
  find(scene)
  if (found.length === 0) return false
  let top = found[0]
  // 爬到 Game 的直接子节点（引导自己那一层）：只藏 GuideNext 会留下它底下的遮罩板
  while (top.parent !== null && top.parent.name !== 'Game' && top.parent.name !== 'Canvas') {
    top = top.parent
  }
  // 兜住两种"再爬就过头"的情形：爬到场景根（parent 为空），或爬到了 Game / Canvas。
  // 只判名字是不够的：一棵没有 Game/Canvas 命名的树会一路爬到**场景根**并把整屏藏掉，
  // 于是量具读到空集合、还报"这一屏零缺陷"（自检第 4 条就是这么抓出来的）。
  if (top.parent === null || top.name === 'Game' || top.name === 'Canvas') return false
  top.active = false
  return true
}

/**
 * Node 侧便利封装：进屏后藏一次，并**再补一次**（板是异步挂上来的，只藏一次常藏空）。
 * 需要每轮都藏的调用方（横扫量具）自己继续按轮次调 `hideGuideBoard`。
 */
export async function hideGuideOverlay(page, waitMs = 250) {
  const first = await page.evaluate(hideGuideBoard)
  await page.waitForTimeout(waitMs)
  const second = await page.evaluate(hideGuideBoard)
  return first || second
}
