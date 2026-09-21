/**
 * 职责：`tools/lib/guide-overlay.mjs` 的自检（`node --test tools/lib/guide-overlay.test.mjs`）。
 * 依赖：node（`node:test`）。
 *
 * <p>浏览器侧函数只碰 `window.cc`，所以这里给 `globalThis.window` 装一棵**假场景树**直接调同一个实现 ——
 * 不拆"纯核心 + 壳"两份，免得留下第二实现（本仓库一直在删的那一族）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { hideGuideBoard, hideGuideOverlay } from './guide-overlay.mjs'

/** 造一棵最小 Cocos 节点树：只需要 name / parent / children / active（activeInHierarchy 由祖先推） */
function node(name, children = []) {
  const n = { name, parent: null, children, active: true }
  for (const c of children) c.parent = n
  return n
}
function withRoots(root) {
  const walk = (n, parentActive) => {
    n.activeInHierarchy = n.active && parentActive
    for (const c of n.children) walk(c, n.activeInHierarchy)
  }
  walk(root, true)
  globalThis.window = { cc: { director: { getScene: () => root } } }
  return root
}
const find = (root, name) => {
  if (root.name === name) return root
  for (const c of root.children) {
    const hit = find(c, name)
    if (hit !== undefined) return hit
  }
  return undefined
}
const gameOf = (root) => find(root, 'Game')

test('引导板挂在 Game 的孙辈时：藏掉 Game 的**直接子节点**那一层，而不是整块 Game', () => {
  const guide = node('GuideLayer', [node('Mask', [node('GuideNext')])])
  const root = withRoots(node('Scene', [node('Canvas', [node('Game', [guide, node('CityPanel')])])]))
  assert.equal(hideGuideBoard(), true)
  assert.equal(guide.active, false, '引导那一层该被藏')
  assert.equal(gameOf(root).active, true, 'Game 必须还亮着 —— 关掉它等于把整屏藏了还报绿')
  assert.equal(find(root, 'CityPanel').active, true)
})

test('没有引导板时返回 false，且不动任何节点（这是"这一屏本来干净"的正常路径）', () => {
  const root = withRoots(node('Scene', [node('Canvas', [node('Game', [node('BagPanel')])])]))
  assert.equal(hideGuideBoard(), false)
  assert.equal(gameOf(root).active, true)
  assert.equal(find(root, 'BagPanel').active, true)
})

test('板直接挂在 Game 下时：藏那颗板本身，Game 与同层面板都不动', () => {
  const game = node('Game', [node('GuideNext'), node('ShopPanel')])
  const root = withRoots(node('Scene', [node('Canvas', [game])]))
  assert.equal(hideGuideBoard(), true)
  assert.equal(find(root, 'GuideNext').active, false, '直接挂的板就藏它自己')
  assert.equal(game.active, true, 'Game 必须还亮着')
  assert.equal(find(root, 'ShopPanel').active, true)
})

test('树里没有 Game / Canvas 可停时：宁可不藏，也不把场景根藏掉（读到空集合还报绿的那个口子）', () => {
  const guide = node('GuideNext')
  const orphan = node('GuideLayer', [guide])
  orphan.parent = null
  const scene = node('Scene', [orphan]) // node() 会把 orphan.parent 指回 scene
  withRoots(scene)
  assert.equal(hideGuideBoard(), false, '爬到了场景根 ⇒ 拒绝')
  assert.equal(scene.active, true, '场景根永远不动')
  assert.equal(orphan.active, true)
  assert.equal(guide.active, true)
})

test('已经是 inactive 的 GuideNext 不算"藏过一次"（避免异步补藏时误报成功）', () => {
  const guide = node('GuideLayer', [node('GuideNext')])
  guide.active = false
  withRoots(node('Scene', [node('Canvas', [node('Game', [guide])])]))
  assert.equal(hideGuideBoard(), false)
})

test('Node 侧封装：两轮里只要有一轮真藏到就报 true（板是异步挂上来的）', async () => {
  const guide = node('GuideLayer', [node('GuideNext')])
  const root = withRoots(node('Scene', [node('Canvas', [node('Game', [guide])])]))
  let calls = 0
  const page = {
    async evaluate(fn) { calls += 1; return fn() },
    async waitForTimeout() {},
  }
  assert.equal(await hideGuideOverlay(page), true)
  assert.equal(calls, 2, '必须补第二轮：只藏一轮常藏空（#391 实测）')
  assert.equal(guide.active, false)
  assert.equal(gameOf(root).active, true)
})
