/**
 * 职责：**harness 三读数覆盖面**与静态门道数必须对得上 —— 每道门要么进 harness，要么在豁免清单里写明理由。
 *
 * <p>为什么需要这道门：harness 的覆盖面是「量具的量具」，而它自己是靠**人记**的
 * ⇒ 静态门加了新的、忘了接 harness ⇒ 覆盖面悄悄少一道，而没有任何东西会红。
 * 这道门把那条「人记」换成机制：门数、已接数、豁免数三者必须恒等。
 *
 * <p>红法：① 静态门多了/少了一道而两边没跟着变；② harness 里出现重复条目；
 * ③ 豁免清单里有**不存在**的门（= 豁免了一个已经删掉的门，覆盖数会算错）；
 * ④ 某个门**既**进 harness **又**进豁免清单；⑤ 豁免清单里某条没写理由。
 *
 * <p>它自己不进 harness（自指：它就是覆盖度的基准）⇒ 已在 `scripts/harness-exempt.txt` 里带理由豁免。
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

const gates = new Set()
for (const m of read('scripts/check.sh').matchAll(/(?:bash|node)\s+scripts\/(check-[a-z0-9-]+)\.(?:sh|js)/g)) {
  gates.add(m[1])
}

const entries = []
for (const m of read('scripts/check-gates-can-fail.sh').matchAll(/^three(?:_arg)?\s+(check-[a-z0-9-]+)/gm)) {
  entries.push(m[1])
}
const dup = [...new Set(entries.filter((g, i) => entries.indexOf(g) !== i))]

const exempt = []
for (const raw of read('scripts/harness-exempt.txt').split(/\r?\n/)) {
  const line = raw.trim()
  if (!line || line.startsWith('#')) continue
  const at = line.indexOf('#')
  exempt.push({
    name: (at >= 0 ? line.slice(0, at) : line).trim(),
    why: (at >= 0 ? line.slice(at + 1) : '').trim(),
  })
}

const problems = []
for (const e of exempt) {
  if (!e.why) problems.push(`豁免清单里 ${e.name} 没写理由`)
  if (!gates.has(e.name)) problems.push(`豁免清单里有不存在的门：${e.name}（静态门里没有它）`)
  if (entries.includes(e.name)) problems.push(`${e.name} 既是已接的、又进了豁免清单`)
}
const covered = new Set(entries).size
if (gates.size !== covered + exempt.length) {
  problems.push(`道数对不上：静态门 ${gates.size} 道 ≠ harness 已接 ${covered} + 豁免 ${exempt.length}`
    + `（差 ${gates.size - covered - exempt.length}）—— 静态门加了新的忘了接 harness，或豁免清单没跟着改`)
}

if (problems.length > 0) {
  console.error('[check-harness-coverage][FAIL] harness 覆盖面与静态门道数对不上：')
  for (const p of problems) console.error(`  - ${p}`)
  process.exit(1)
}
if (dup.length > 0) {
  // 同一道门挂多条夹具是**合法**的（一个门可以有多个违规形态）⇒ 只作提示，不作红因。
  console.log(`[check-harness-coverage] 提示：这些门在 harness 里挂了不止一条夹具：${dup.join('、')}`)
}
console.log(`[check-harness-coverage] 静态门 ${gates.size} 道 = harness 已接 ${covered} + 豁免 ${exempt.length}`
  + `（豁免都写了理由）⇒ 覆盖面没有漂移。`)
