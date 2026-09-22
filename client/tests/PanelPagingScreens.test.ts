/**
 * 职责：把「行区画不下就必须翻得到」这一族钉成一条量具 —— #307 在各面板的落地判据。
 * 依赖：node（`node --test`）。
 *
 * <p>与 `tests/PanelPaging.test.ts`（算式本身）和 `tests/StagePaging.test.ts`（关卡特有那条
 * 手抄减一）不重复：这里守的是**每一屏共同**的四件事 ——
 * <ol>
 *   <li>接在 `PanelPaging` 那一份算式上，不在本屏养第二份分页算术；</li>
 *   <li>行区真的按页切段（`pageWindow`），而不是 `slice(0, 容量)` 一刀切之后再补一句
 *       「另有 N 项未显示」—— 后者就是 #307 说的"换了件衣服的看不见"；</li>
 *   <li>本屏不再拼那句「另有 N 项未显示」（谓词取缺陷本身：接了分页之后它的分母恒为 0，
 *       留着就是同一句文案的第二处写手）；</li>
 *   <li>全树不再引用 `game/ui/TruncatedList` —— 那个模块唯一的用途就是产出第 3 条那句
 *       够不着的话，留着它就会有人下一屏接着用。</li>
 * </ol>
 *
 * <p>三条负向判据一律**先剥行注释再判**（`d7f0f58` 的做法）：这些文件的注释里正写着"从前这里
 * `slice(0, maxRows)` 一刀切"，拿散文当代码判会假红 —— 判结构不判散文。
 *
 * <p>"翻页键在没数据那一态不许露着"（#345）不在这里守：源码里 `active = false` 挪个位置就假红，
 * 而那一态运行时量具能真跑到（`tools/verify-panel-paging-runtime.mjs`、`tools/verify-autotrain-runtime.mjs`
 * 的第二相），量得到的不靠猜。
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
  'ArmyPanelView',
  'MailPanelView',
  'BattleReportPanelView',
  'QuestPanelView',
  'ShopPanelView',
  'AvatarFramePanelView',
  'BagPanelView',
]

for (const view of PAGED_VIEWS) {
  const source = () => fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts',
    'scene', `${view}.ts`), 'utf8')
  /** 剥掉行注释之后的代码本体：负向判据只量结构，不量旁边写了什么。 */
  const code = () => source().replace(/\/\/[^\n]*/g, '')

  test(`${view} 接在 PanelPaging 那一份算式上`, () => {
    assert.ok(source().includes("from '../game/ui/PanelPaging'"),
      `没引 PanelPaging ⇒ ${view} 要么不分页，要么又养出一份自己的分页算术（将来必然分叉）`)
  })

  test(`${view} 的行区按页切段，不是切一刀再补一句"另有 N 项未显示"`, () => {
    assert.ok(source().includes('pageWindow('),
      `没有 pageWindow ⇒ ${view} 只画第一屏，剩下的行玩家够不着（#307）`)
  })

  // 光看"有没有 pageWindow"判不住：算完页号却仍然 `slice(0, 容量)` 照样绿（#452 的独立审查
  // 提的第⑥条）。分页之后画的那一段一定从窗口起点开始（`slice(slot.start, slot.end)`），
  // 所以任何从头切一刀的 `.slice(0,` 都是"算完窗口又丢掉"—— 三种历史写法（`slice(0, capacity)`、
  // `slice(0, maxRows)`、`slice(0, drawn)`）被这一条一起盖住，不必逐个数别名。
  test(`${view} 不许留着"一刀切到容量"的旧写法`, () => {
    assert.equal(/\.slice\(\s*0\s*[,)]/.test(code()), false,
      `${view} 里还有从头切一刀的 .slice(0, ⇒ 第一屏之外的那些行又没人能翻到（#307 换了件衣服）`)
  })

  test(`${view} 不再拼那句够不着的话`, () => {
    assert.equal(code().includes('truncatedNotice'), false,
      `${view} 又拼「另有 N 项未显示」⇒ 话说诚实了，但那 N 行永远拿不到（#307 原话）`)
  })
}

test('全树不再引用 TruncatedList（那句"另有 N 项未显示"的唯一产地）', () => {
  const root = path.join(repoRoot(), 'client', 'assets', 'scripts')
  const hits: string[] = []
  const walk = (dir: string): void => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      } else if (entry.name.endsWith('.ts')
        // 同样先剥行注释：判的是"还有没有代码依赖它"，不是"有没有人提到过它"
        && fs.readFileSync(full, 'utf8').replace(/\/\/[^\n]*/g, '').includes('TruncatedList')) {
        hits.push(path.relative(root, full))
      }
    }
  }
  walk(root)
  assert.deepEqual(hits, [], `这些文件还引用 TruncatedList：${hits.join('、')}`)
  assert.ok(!fs.existsSync(path.join(root, 'game', 'ui', 'TruncatedList.ts')),
    'TruncatedList.ts 还在：留着它，下一屏截断就会又用那句"另有 N 项"而不是分页')
})
