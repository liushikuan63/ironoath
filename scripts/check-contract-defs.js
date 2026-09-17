// 职责：CI 静态检查 —— contract/proto 各 schema 的 $defs **同名必须同形**。
// 症状族：生成物全部落在同一个 Java 包（web/dto/generated）与同一个 TS 命名空间里，
//   所以 `$defs` 的名字是**跨文件的全局命名空间**：后处理的那份会整份覆盖前一份。
//   两个形状相同的副本无害（army/bag/city 一直是这么复用 ResourceAmount 的），
//   一旦形状不同，被覆盖那侧的消费者就会拿到另一个类型。
// 真实事故：B20 新加 tech.schema.json 时给 ResourceAmount.type 写了内联枚举（String），
//   而 city/army/bag 三份指的是 #/$defs/ResourceType（枚举）—— 编译当场红在三处老代码上。
//   **这次是编译器接住的**：因为 Java 类型不同。若分歧只在 minimum / enum 取值 / 字段可空性上，
//   编译照样过，症状变成"某端的 TS 类型与另一端不一致"或"校验边界悄悄换了家"。
// 判定：同名 def 的**结构**（去掉 description 之后的 JSON）必须一致；description 允许不同
//   （那是散文，不是协议），其余任何分歧都失败，并指出是哪两个文件。
// 依赖：node。
const fs = require('fs')
const path = require('path')

const DIR = 'contract/proto'
const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.schema.json')).sort()
if (files.length < 15) {
  console.error('[check-contract-defs][FAIL] 只看到 ' + files.length
    + ' 份 schema（预期 15 份以上）—— 扫不到文件时这条检查就只剩"全绿"可报了')
  process.exit(1)
}

/** 递归去掉 description：散文不算协议形状。 */
function structural(node) {
  if (Array.isArray(node)) return node.map(structural)
  if (node && typeof node === 'object') {
    const out = {}
    for (const key of Object.keys(node).sort()) {
      if (key === 'description') continue
      out[key] = structural(node[key])
    }
    return out
  }
  return node
}

const byName = new Map()
for (const file of files) {
  const doc = JSON.parse(fs.readFileSync(path.join(DIR, file), 'utf8'))
  const defs = doc.$defs || {}
  for (const name of Object.keys(defs)) {
    const entry = byName.get(name) || { shapes: new Map(), files: [] }
    const json = JSON.stringify(structural(defs[name]))
    if (!entry.shapes.has(json)) entry.shapes.set(json, [])
    entry.shapes.get(json).push(file)
    entry.files.push(file)
    byName.set(name, entry)
  }
}

let shared = 0
const problems = []
for (const [name, entry] of [...byName.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
  if (entry.files.length < 2) continue
  shared++
  if (entry.shapes.size > 1) {
    const detail = [...entry.shapes.values()].map((fs2) => fs2.join('+')).join(' ｜ ')
    problems.push(`${name} 在 ${entry.files.length} 份 schema 里出现，但有 ${entry.shapes.size} 种形状：`
      + `${detail}。生成物同包同名，后一份会整份覆盖前一份 —— `
      + '要么把它们改成逐字段一致（ResourceType / ResourceAmount 就是四份文件抄同一份形状的先例），'
      + '要么给这一份换个不冲突的名字')
  }
}

const total = byName.size
console.log('[check-contract-defs] ' + files.length + ' 份 schema，' + total
  + ' 个 def 名，其中跨文件复用的 ' + shared + ' 个：形状全部一致')
if (problems.length > 0) {
  console.error('[check-contract-defs][FAIL] ' + problems.length + ' 条：')
  problems.forEach((p) => console.error('   - ' + p))
  process.exit(1)
}
console.log('[check-contract-defs] 同名 def 全部同形，没有任何一份生成物会被悄悄覆盖。')
