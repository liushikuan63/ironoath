// 职责：CI 静态检查 —— 错误码枚举的两条硬不变量（客户端只能按 code 分支，所以这两条一旦破了
//       没有任何运行时报错，只有玩家看到答非所问的提示）。
//   R1 码值唯一：两个语义共用一个数字 ⇒ 客户端无法区分，而两边的中文文案还不一样。
//   R2 码值必须落在 ErrorCode 类注释里声明的段位内（那段写着"新增错误码必须落在对应段位，
//      禁止跨段复用数字"）。拦的是 70000 / 500 / 100041 这类手滑 —— 段位本身是给"哪个系统的码"
//      定位用的，跑出错段就等于把两个系统的号段混成一段。
// 段位表从类注释里解析（那才是唯一真相），但**解析到的段数少于 15 就失败**：
// 否则注释一改就把整条检查变成空转 —— 半瞎的绿灯比没有检查危险。
// 依赖：node。零引用码的清单只打印不失败（见文件末尾的说明）。
const fs = require('fs')
const path = require('path')

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (/target|node_modules|build-test|\.git/.test(p)) continue
      walk(p, out)
    } else if (e.name.endsWith('.java')) out.push(p)
  }
  return out
}

const files = walk('server', [])
const defFile = files.find((f) => f.endsWith(path.join('common', 'ErrorCode.java')))
if (!defFile) {
  console.error('[check-error-codes][FAIL] 找不到 ErrorCode.java，检查无法进行')
  process.exit(1)
}
const def = fs.readFileSync(defFile, 'utf8')
const head = def.split(/\n/).slice(0, 40).join('\n')

const SEGMENTS = []
const seg = /(\d+)~(\d+)/g
let s
while ((s = seg.exec(head)) !== null) SEGMENTS.push([Number(s[1]), Number(s[2])])
if (SEGMENTS.length < 15) {
  console.error('[check-error-codes][FAIL] 只从类注释里解析到 ' + SEGMENTS.length
    + ' 个段位（预期至少 15）。注释格式一改这条检查就会静默失效，所以宁可失败')
  process.exit(1)
}

const codes = new Map()
const decl = /([A-Z][A-Z0-9_]+)\s*\(\s*(\d+)\s*,\s*"([^"]*)"\s*\)/g
let d
while ((d = decl.exec(def)) !== null) codes.set(d[1], Number(d[2]))
if (codes.size < 100) {
  console.error('[check-error-codes][FAIL] 只解析到 ' + codes.size + ' 个错误码，疑似声明格式变了')
  process.exit(1)
}

const problems = []

const byValue = new Map()
for (const [name, value] of codes) {
  if (!byValue.has(value)) byValue.set(value, [])
  byValue.get(value).push(name)
}
for (const [value, names] of byValue) {
  if (names.length > 1) {
    problems.push(`码值 ${value} 被 ${names.join(' 与 ')} 共用 —— 客户端按码分支时分不开这两件事`)
  }
}

const inSegment = (v) => v === 0 || SEGMENTS.some(([lo, hi]) => v >= lo && v <= hi)
for (const [name, value] of codes) {
  if (!inSegment(value)) {
    problems.push(`${name} = ${value} 不在任何已声明段位内（段位表见 ErrorCode 类注释）`)
  }
}

/* 零引用码：**必须有出处，否则失败**。本轮删掉了 18 个"既没有任何批次文件声明、也没有任何代码引用"
   的码（它们只活在这份枚举里，是当初起草时凭空列的），所以现在唯一合法的"声明但不抛"理由是
   某个批次文件的错误码清单里列了它 —— 那就必须写成 `契约预列：<文件>`，
   而**卡口会去那个文件里核对是否真的提到这个码**，出处不许凭手感写。 */
const used = new Set()
for (const f of files) {
  if (f === defFile) continue
  const src = fs.readFileSync(f, 'utf8')
  const r = /ErrorCode\.([A-Z][A-Z0-9_]+)/g
  let u
  while ((u = r.exec(src)) !== null) used.add(u[1])
}
const defLines = def.split('\n')
const declLineOf = (name) =>
  defLines.find((l) => new RegExp('^\\s*' + name + '\\(\\d+,').test(l)) || ''
const zeroUse = [...codes.keys()].filter((n) => n !== 'OK' && !used.has(n))

for (const name of zeroUse) {
  const cite = declLineOf(name).match(/契约预列：\s*([^\s（(]+)/)
  if (!cite) {
    problems.push(`${name} 声明了却没有任何代码抛出它，也没写「契约预列：<批次文件>」出处。`
      + '要么接上守卫，要么删掉这一行 —— 客户端为一条永不出现的失败写分支，比少一个码更糟')
    continue
  }
  if (!fs.existsSync(cite[1])) {
    problems.push(`${name} 的出处指向 ${cite[1]}，但这个文件不存在`)
    continue
  }
  if (!fs.readFileSync(cite[1], 'utf8').includes(name)) {
    problems.push(`${name} 声称契约预列于 ${cite[1]}，但那个文件里没提到这个码 —— 出处写错了，或规格已改`)
  }
}

console.log('[check-error-codes] 声明 ' + codes.size + ' 个码，段位 ' + SEGMENTS.length + ' 段')
console.log('[check-error-codes] 零引用但已核过批次出处的：' + zeroUse.length + ' 个 -> '
  + (zeroUse.length ? zeroUse.join(' ') : '（无）'))

if (problems.length > 0) {
  console.error('[check-error-codes][FAIL] ' + problems.length + ' 条：')
  problems.forEach((p) => console.error('   - ' + p))
  process.exit(1)
}
console.log('[check-error-codes] 码值唯一且都落在声明段位内。')
