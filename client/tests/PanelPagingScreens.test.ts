/**
 * 职责：把「行区画不下就必须翻得到」这一族钉成一条量具 —— #307 在各面板的落地判据。
 * 依赖：node（`node --test`）。
 *
 * <p>与 `tests/PanelPaging.test.ts`（算式本身）和 `tests/StagePaging.test.ts`（关卡特有那条
 * 手抄减一）不重复：这里守的是**每一屏共同**的六件事 ——
 * <ol>
 *   <li>接在 `PanelPaging` 那一份算式上，不在本屏养第二份分页算术；</li>
 *   <li>行区真的按页切段（`pageWindow`），而不是 `slice(0, 容量)` 一刀切之后再补一句
 *       「另有 N 项未显示」—— 后者就是 #307 说的"换了件衣服的看不见"；</li>
 *   <li>本屏不再留着"从头切一刀"那一种写法；</li>
 *   <li>本屏不再拼那句「另有 N 项未显示」（谓词取缺陷本身：接了分页之后它的分母恒为 0，
 *       留着就是同一句文案的第二处写手）；</li>
 *   <li>本屏没有自己再声明一个与算式同名的函数（第 1 条只看 import 串，被这一条补齐）；</li>
 *   <li>名单与"实际接了分页的屏"双向相等 —— 名单少一行就等于那一屏从此没有判据。</li>
 * </ol>
 * 另有两条全树的：不再引用 `game/ui/TruncatedList`（那句"另有 N 项未显示"的唯一产地），
 * 且它连 `.ts.meta` 都不许留（孤儿元数据指向一个不存在的脚本）。
 *
 * <p>六条一律**先把注释剥干净再判**：`d7f0f58` 定了"负向判据剥行注释"（第一版被两处描述旧写法的
 * 注释假红两次），本轮把正向两条也接上（它们从前一句注释就能满足），并把剥法改成只按行内规则
 * （见 `codeOf` 的注释：跨行的块注释正则能被一行注释打开，吞掉中间真代码）。
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

/**
 * 只按**行内**规则剥注释，绝不引入跨行状态（#456 改的）：
 * ① 整行是注释（`//` 开头、块注释的起止、以及 JSDoc 那种 ` * ` 续行）⇒ 丢掉整行；
 * ② 代码行尾随的注释 ⇒ 从 `//` 处切掉。
 *
 * <p>为什么不用 `/\/\*[\s\S]*?\*\//`：行注释里出现一次通配写法就会把"块注释"就地打开，
 * 一路吃到文件里下一个真正的结束符，中间几十行**真代码**从判据里消失 —— 那是假绿方向，
 * 比"注释被当成代码"严重得多（独立审查实测复现：补这样一行注释后，第 472 行植入的
 * 一刀切四条判据全绿）。改成行内规则后，行内并排写的块注释不再剥，那种形状只会让负向判据
 * 看见更多代码 ⇒ 多报红（fail-closed），不会漏报。
 */
function codeOf(source: string): string {
  return source
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\/\*|\*|\*\/)/.test(line))
    .map((line) => line.replace(/\/\/.*$/, ''))
    .join('\n')
}

/** `PanelPaging` 导出的算式名，**现取不写死**：将来加一个导出，第 5 条自动覆盖到它。 */
const PAGING_EXPORTS: string[] = fs.readFileSync(path.join(repoRoot(), 'client', 'assets',
  'scripts', 'game', 'ui', 'PanelPaging.ts'), 'utf8')
  .split('\n')
  .map((line) => /^export function (\w+)/.exec(line)?.[1] ?? null)
  .filter((name): name is string => name !== null)

for (const view of PAGED_VIEWS) {
  const source = () => fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts',
    'scene', `${view}.ts`), 'utf8')
  const code = () => codeOf(source())

  test(`${view} 接在 PanelPaging 那一份算式上`, () => {
    assert.ok(code().includes("from '../game/ui/PanelPaging'"),
      `没引 PanelPaging ⇒ ${view} 要么不分页，要么又养出一份自己的分页算术（将来必然分叉）`)
  })

  test(`${view} 的行区按页切段，不是切一刀再补一句"另有 N 项未显示"`, () => {
    assert.ok(code().includes('pageWindow('),
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

  // 第 1 条只查到"这个 import 串在不在"，本屏自己再声明一个**同名**函数它就满意了 ——
  // 而那份本地实现才是真正在算的那一份（#456 独立审查用恒返回 start 0 的本地 pageWindow 复现，四判据全绿）。
  test(`${view} 没有在本屏养第二份分页算术`, () => {
    for (const name of PAGING_EXPORTS) {
      assert.equal(new RegExp(`(function ${name}\\s*\\(|(const|let) ${name}\\s*=)`).test(code()), false,
        `${view} 自己声明了 ${name} ⇒ 名义上引 PanelPaging，实际算的是另一份（两份必然分叉）`)
    }
  })
}

// 名单要能被证伪：删掉一行 ⇒ 那一屏从此没有判据，而门不会响。这里拿"实际调了 pageWindow 的屏"
// 双向比集合（#456）—— 漏登记的屏要红，名单里留着已经不接的屏也要红。
test('PAGED_VIEWS 与实际接了分页的屏一一对应（既不漏登记，也不留永不红的空判据）', () => {
  const dir = path.join(repoRoot(), 'client', 'assets', 'scripts', 'scene')
  const callers = fs.readdirSync(dir)
    .filter((name) => name.endsWith('.ts')
      && codeOf(fs.readFileSync(path.join(dir, name), 'utf8')).includes('pageWindow('))
    .map((name) => name.replace(/\.ts$/, ''))
    .sort()
  assert.deepEqual([...PAGED_VIEWS].sort(), callers,
    `名单 ${PAGED_VIEWS.length} 屏 / 实际调用 ${callers.length} 屏 ⇒ 要么某屏接了分页却没门，要么某条判据已经永不生效`)
})

test('全树不再引用 TruncatedList（那句"另有 N 项未显示"的唯一产地）', () => {
  const root = path.join(repoRoot(), 'client', 'assets', 'scripts')
  const hits: string[] = []
  const walk = (dir: string): void => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      } else if (entry.name.endsWith('.ts')
        // 同样只量代码：判的是"还有没有代码依赖它"，不是"有没有人提到过它"
        && codeOf(fs.readFileSync(full, 'utf8')).includes('TruncatedList')) {
        hits.push(path.relative(root, full))
      }
    }
  }
  walk(root)
  assert.deepEqual(hits, [], `这些文件还引用 TruncatedList：${hits.join('、')}`)
  assert.ok(!fs.existsSync(path.join(root, 'game', 'ui', 'TruncatedList.ts')),
    'TruncatedList.ts 还在：留着它，下一屏截断就会又用那句"另有 N 项"而不是分页')
  // 删 .ts 不删 .meta 会留下一颗指向不存在脚本的元数据（构建里那句
  // `Script "<uuid>" attached to "Game" is missing or invalid` 就是这一族的形状，#456）
  assert.ok(!fs.existsSync(path.join(root, 'game', 'ui', 'TruncatedList.ts.meta')),
    'TruncatedList.ts.meta 还留着：源文件已删，这颗孤儿 meta 指向一个不存在的脚本')
})
