// 职责：见同名 check-dangling-test-refs.sh。拦住「注释里说有个测试守着，而那个测试不存在」这一族缺陷。
// 为什么值得做成门：这类引用比不写注释更坏 —— 下一个人 grep 不到就以为不用自己查，
//   而它承诺的正是本该由门保证的那件事。本仓已清完 31 处（台账 #434~#438），
//   这道门负责让「再抄一次名字忘了改」当场红，而不是靠人记得回扫。
'use strict'

const { execFileSync } = require('child_process')
const fs = require('fs')

// 框架注解不是「本仓的守卫」，一律不算悬空引用。
const FRAMEWORK = new Set([
  'SpringBootTest', 'ParameterizedTest', 'RepeatedTest', 'TestFactory',
  'TestTemplate', 'NestedTest', 'TestInstance', 'TestReporter',
])

// 白名单：文件里确实写了这个类名，但那是「引用契约里那句不实承诺当靶子」，不是把它当现行判据。
// 每条都必须仍然命中 —— 现场变了（真修了或写法漂了）而白名单没跟着改，门判红：白名单不许悄悄烂掉。
const ALLOW = [
  {
    file: 'server/game-web/src/test/java/com/ironoath/web/NationPayEnumParityTest.java',
    name: 'NationContractParityTest',
    why: '该测试类的 javadoc 在引用契约里那句不实承诺当靶子，正是它存在的理由（台账 #434 记过）',
  },
]

// 已登记待修：留给"承诺先写、类还没建"且**短期不会建**的场景。
// 本门扫的是工作树，所以并行会话的在途承诺会在这里显形 —— 那**不登记**：门当场判红才是对的，
// 让写承诺的那个人自己把类补上（2026-09-22 上线当天就抓到三条这种在途引用，登记反而会把别人的欠账藏进我的名单）。
const KNOWN_OPEN = []

const SCAN = [/^server\/.*\.java$/, /^client\/assets\/scripts\/.*\.ts$/, /^tools\/.*\.mjs$/, /^scripts\/.*\.js$/]

// 门自己不在扫描范围内：ALLOW/KNOWN_OPEN 与 --self-test 的样例都是**字面量类名**，
// 自查会把这些必需品判成悬空引用。门的口径由 --self-test 负责（它会验"新造的名字必须被抓到"）。
const SELF = new Set(['scripts/check-dangling-test-refs.js'])

/** 仓库里真实声明过的 *Test 类名。按声明取而不按文件名取：嵌套类也算存在。 */
function declaredNames (files) {
  const out = new Set()
  for (const f of files.filter((p) => p.endsWith('.java'))) {
    for (const m of read(f).matchAll(/\b(?:class|interface|record|enum|@interface)\s+([A-Za-z0-9_]*Tests?)\b/g)) {
      out.add(m[1])
    }
  }
  return out
}

/**
 * 一个文件里出现的测试类形状引用。
 * 左边界含 `*`：通配写法（{@code *StoreContractTest}）指的是一个家族而不是某个类，不是悬空名。
 */
function refsIn (text) {
  const hits = new Set()
  for (const m of text.matchAll(/(?<![A-Za-z0-9_*])([A-Z][A-Za-z0-9]{3,}Test)\b/g)) hits.add(m[1])
  return hits
}

function read (f) {
  return fs.readFileSync(f, 'utf8')
}

function tracked () {
  return execFileSync('git', ['ls-files'], { encoding: 'utf8' }).trim().split('\n')
}

function scan (files) {
  const declared = declaredNames(files)
  const found = []
  for (const f of files.filter((p) => !SELF.has(p) && SCAN.some((re) => re.test(p)))) {
    for (const name of refsIn(read(f))) {
      if (FRAMEWORK.has(name) || declared.has(name)) continue
      found.push({ file: f, name })
    }
  }
  return found
}

function run () {
  const verbose = process.argv.includes('--verbose')
  const found = scan(tracked())
  const key = (h) => `${h.file}#${h.name}`
  const registered = [...ALLOW, ...KNOWN_OPEN]
  const allowed = new Set(registered.map(key))
  const stale = registered.filter((a) => !found.some((h) => key(h) === key(a)))
  const bad = found.filter((h) => !allowed.has(key(h)))
  for (const h of (verbose ? found : bad)) {
    console.log(`[dangling-test-refs] ${h.file} 引用了不存在的测试类 ${h.name}`)
  }
  for (const a of KNOWN_OPEN) {
    if (found.some((h) => key(h) === key(a))) {
      console.log(`[dangling-test-refs] 已登记待修：${a.file}#${a.name} —— ${a.why}`)
    }
  }
  for (const a of stale) {
    console.log(`[dangling-test-refs] 登记已失效（那个引用现在扫不到了）：${a.file}#${a.name} —— 现场变了就删登记，别留着`)
    bad.push(a)
  }
  console.log(`[dangling-test-refs] 命中 ${found.length} 处（白名单 ${ALLOW.length}、待修 ${KNOWN_OPEN.length}），未登记 ${bad.length} 处`)
  return bad.length === 0 ? 0 : 1
}

/**
 * 自检：门的口径本身要能失败。
 * 五条形状 + 一条「白名单必须仍在现场命中」的逻辑，全部按预期成立才退 0。
 */
function selfTest () {
  let bad = 0
  const cases = [
    ['// 判据见 FooMissingTest', ['FooMissingTest'], '注释里的悬空名要被抓到'],
    ['public class FooMissingTest {', ['FooMissingTest'], '声明处也按形状抓到，之后由 declared 过滤掉'],
    ['@SpringBootTest classes', ['SpringBootTest'], '形状层照收框架名，过滤发生在 scan 那一步'],
    ['extends ArmyStoreContractTest', ['ArmyStoreContractTest'], '只许取整名，不许从中间起匹配'],
    ['见 {@code *StoreContractTest} 那族', [], '通配写法不是类名'],
    ['class InnerGuardTest', ['InnerGuardTest'], '嵌套声明的取法要成立'],
  ]
  for (const [text, hits, why] of cases) {
    const got = [...refsIn(text)]
    const ok = got.length === hits.length && hits.every((h) => got.includes(h))
    if (!ok) { console.log(`[self-test] 不通过：${why}（refsIn 给出 ${JSON.stringify(got)}，期望 ${JSON.stringify(hits)}）`); bad++ }
  }
  // 两道过滤各验一条：框架名与「已声明」都不该进最终结果
  if (!FRAMEWORK.has('SpringBootTest')) { console.log('[self-test] 不通过：框架名单漏了 SpringBootTest'); bad++ }
  if (refsIn('extends ArmyStoreContractTest').has('StoreContractTest')) {
    console.log('[self-test] 不通过：左边界失效，长类名被数成短名'); bad++
  }
  // declared 侧的过滤要在真仓库上成立：仓库里有几百个声明过的 *Test，扫描结果里不该出现它们
  const declared = declaredNames(tracked())
  if (declared.size < 50) { console.log(`[self-test] 不通过：declaredNames 只取到 ${declared.size} 个，口径失效`); bad++ }
  // 两份登记都不能靠信仰：每条此刻必须真能在扫描结果里扫到，否则 run() 的 stale 分支永远不会走
  const live = scan(tracked())
  for (const a of [...ALLOW, ...KNOWN_OPEN]) {
    if (!live.some((h) => h.file === a.file && h.name === a.name)) {
      console.log(`[self-test] 不通过：登记条目当场扫不到 ${a.file}#${a.name}`); bad++
    }
  }
  console.log(`[self-test] ${bad === 0 ? '全部通过' : `${bad} 条不通过`}`)
  return bad === 0 ? 0 : 2
}

if (require.main === module) {
  process.exit(process.argv.includes('--self-test') ? selfTest() : run())
}
module.exports = { refsIn, declaredNames, scan, ALLOW }

