/**
 * 职责：目标搜索那两颗翻页键的显隐判据（#345 口径「点了没反应的按钮不该露着」的续）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯两件事：
 * <ol>
 *   <li><b>没搜过就必须收着</b>。视图里 `paintPageButtons` 从前只在 `render()` 里被调，
 *       而 `render()` 在 `response === null` 时第一行就 return ⇒ 首搜之前两颗键一直露着，
 *       点下去 `changePage` 又直接 return（探针实跑：那一屏只有 3 颗控件字、榜行 0 颗）。</li>
 *   <li><b>判据真的被视图用到、且在 `render()` 之外也判过一次</b>。逻辑层的单测碰不到 Cocos 节点树，
 *       所以这一条只能对源码断言 —— 删掉建控件后那一次调用，这里就红。</li>
 * </ol>
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

import { pagerKeysVisible } from '../assets/scripts/game/power/PowerPanel'

/** 从 `client/` 或 `client/build-test/…` 都能走到仓库根：往上找带 `client/assets` 的那一层。 */
function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 8; i += 1) {
    if (fs.existsSync(path.join(dir, 'client', 'assets', 'scripts'))) return dir
    const up = path.resolve(dir, '..')
    if (up === dir) break
    dir = up
  }
  throw new Error(`找不到仓库根（从 ${process.cwd()} 往上八层都没有 client/assets/scripts）`)
}

test('没搜过的屏幕不给翻页键：哪怕页数看着大于 1', () => {
  assert.equal(pagerKeysVisible(false, 5), false)
  assert.equal(pagerKeysVisible(false, 1), false)
})

test('搜过之后只有一页（含零行）仍然收着，两页才露出来', () => {
  assert.equal(pagerKeysVisible(true, 0), false)
  assert.equal(pagerKeysVisible(true, 1), false)
  assert.equal(pagerKeysVisible(true, 2), true)
})

test('视图真的用这条判据，而且在 render 之外还判过一次', () => {
  const src = fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts', 'scene',
    'TargetSearchView.ts'), 'utf8')
  // 旧写法留在源码里就意味着有人把判据换回去了：那一版首搜前两颗键一直露着
  assert.equal(src.includes('const paged = pages > 1'), false,
    'paintPageButtons 又用回了裸的 pages > 1 ⇒ 首搜前的空转键会重新出现')
  assert.ok(src.includes('pagerKeysVisible(this.searched, pages)'),
    'paintPageButtons 没走 pagerKeysVisible ⇒ 这一维的判据在视图里失联了')
  // 建控件后那一次显式调用：render() 在 response === null 时根本不会跑，少了它就等于没判
  assert.ok(src.includes('this.paintPageButtons(1)'),
    '缺少"建完就判一次"的那句（半径键同一处已经判过）⇒ 首次搜索前两颗翻页键又没人收')
})
