/**
 * 职责：部队面板分页的守卫用例（#307「装不下就必须拿得到」在兵种这一屏的落地）。
 * 依赖：node（`node --test`）。
 *
 * <p>只守两件事，且**不做表达式形状的正向断言**（一提局部变量就假红，台账 #450 的审查结论）：
 * <ol>
 *   <li><b>不许再回到"切掉 + 说一句另有 N 项"</b>。全兵种页 20 行从前只画 4 行，剩下 16 行的兵
 *       玩家永远够不着；现在那一格是翻页行。判据用"这个文件不再引 `truncatedNotice`"——
 *       谁把截断写回来，那句够不着的话就会重新出现在屏上，import 必然跟着回来。</li>
 *   <li><b>分页算术只有 `PanelPaging` 那一份</b>：共几页、夹到哪一页、切哪一段必须同一个
 *       `perPage`，抄一份就是留一个将来会分叉的口径（#307 的直接教训）。</li>
 * </ol>
 * <p>页号语义（换筛选归零 / 写后刷新不归零）由运行时探针 `tools/verify-autotrain-runtime.mjs`
 * 与一次性走页探针钉，不在这里对源码做位置断言。
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
  'scene', 'ArmyPanelView.ts'), 'utf8')

test('兵种这一屏不再说「另有 N 项未显示」：够不着的兵换成翻页行', () => {
  assert.equal(viewSource().includes('truncatedNotice'), false,
    '又把截断那句写回来了 ⇒ 剩下的兵没人能翻到（#307 那句"话说诚实了，但下面的东西永远拿不到"）')
})

test('部队面板接在 PanelPaging 那一份算式上', () => {
  assert.ok(viewSource().includes("from '../game/ui/PanelPaging'"),
    '没引 PanelPaging ⇒ 这一屏要么还在一刀切，要么又养出一份自己的分页算术')
})
