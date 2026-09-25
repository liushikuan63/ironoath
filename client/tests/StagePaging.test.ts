/**
 * 职责：关卡面板分页的守卫用例（#307「装不下就必须拿得到」在关卡这一屏的落地）。
 * 依赖：node（`node --test`）。
 *
 * <p>分页算术本身由 `tests/PanelPaging.test.ts` 覆盖（共几页、夹页、半开区间、让格），
 * 这里**不重复**那批用例，只守关卡这一屏特有的两件事：
 * <ol>
 *   <li>不许再手抄一遍"让最后一格"的算式 —— 从前这里写的是 `capacity - 1`，与 `contentPerPage`
 *       分叉之后就是"另有 46 关未显示"这种玩家永远够不着的内容（#307 的原话）。</li>
 *   <li>这一屏必须真的接在 `PanelPaging` 那一份算式上（按 import 判，不按某句表达式判：
 *       表达式一提局部变量就假红）。</li>
 * </ol>
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

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

const viewSource = () => fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts',
  'scene', 'StagePanelView.ts'), 'utf8')

test('关卡面板不许再手抄"让最后一格"的算式：共几页与切哪一段必须同一个 perPage', () => {
  assert.equal(viewSource().includes('capacity = Math.max(1, capacity - 1)'), false,
    '手抄了一遍减一 ⇒ 与 contentPerPage 分叉，被截断的关又没人能翻到（#307）')
})

test('关卡面板接在 PanelPaging 那一份算式上', () => {
  assert.ok(viewSource().includes("from '../game/ui/PanelPaging'"),
    '没引 PanelPaging ⇒ 这一屏要么不分页，要么又养出一份自己的分页算术')
})
