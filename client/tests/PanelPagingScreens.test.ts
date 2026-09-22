/**
 * 职责：把「行区画不下就必须翻得到」这一族钉成一条量具 —— #307 在各面板的落地判据。
 * 依赖：node（`node --test`）。
 *
 * <p>与 `tests/PanelPaging.test.ts`（算式本身）和 `tests/StagePaging.test.ts`（关卡特有那条
 * 手抄减一）不重复：这里守的是**每一屏共同**的两件事 ——
 * <ol>
 *   <li>接在 `PanelPaging` 那一份算式上，不在本屏养第二份分页算术；</li>
 *   <li>行区真的按页切段（`pageWindow`），而不是 `slice(0, 容量)` 一刀切之后再补一句
 *       「另有 N 项未显示」—— 后者就是 #307 说的"换了件衣服的看不见"。</li>
 * </ol>
 *
 * <p>"翻页键在没数据那一态不许露着"（#345）不在这里守：源码里 `active = false` 挪个位置就假红，
 * 而那一态运行时量具能真跑到（`tools/verify-panel-paging-runtime.mjs`），量得到的不靠猜。
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

/** 已接真分页的面板。每接一屏加一行 —— 加进去的那一刻这一行必须已经能过。 */
const PAGED_VIEWS = [
  'SocialPanelView',
  'StagePanelView',
  'TargetSearchView',
  'MailPanelView',
  'BattleReportPanelView',
  'QuestPanelView',
  'ShopPanelView',
  'AvatarFramePanelView',
]

for (const view of PAGED_VIEWS) {
  const source = () => fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts',
    'scene', `${view}.ts`), 'utf8')

  test(`${view} 接在 PanelPaging 那一份算式上`, () => {
    assert.ok(source().includes("from '../game/ui/PanelPaging'"),
      `没引 PanelPaging ⇒ ${view} 要么不分页，要么又养出一份自己的分页算术（将来必然分叉）`)
  })

  test(`${view} 的行区按页切段，不是切一刀再补一句"另有 N 项未显示"`, () => {
    assert.ok(source().includes('pageWindow('),
      `没有 pageWindow ⇒ ${view} 只画第一屏，剩下的行玩家够不着（#307）`)
  })
}
