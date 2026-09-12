// 职责：传给 requirePermission 的权限位必须存在于 role_permission 的 permission 列。
// 为什么必须是卡口：填错时**什么都不会发生** —— 查表查不到就当"没权限"或"有权限"，
// 具体取决于实现，但一定不报错。本项目已两次踩到（把行 id perm_alliance_expand 当权限位填）。
//
// 两条自我约束（都是这一轮真踩过的）：
// ① 表文件名要带 .json —— 上一版忘了带，表读成空集，于是"外键全对"与"权限全缺"同时成立，
//    一条永远不会失败的检查比没有检查更糟。这里表读不到就直接失败。
// ② 调用可能折行 —— 只按单行匹配会漏掉跨行的调用，于是"用到的权限位"少报，
//    检查跟着假绿。所以按括号配平取整段实参。
const fs = require('fs')
const path = require('path')

const TABLE = 'contract/config/role_permission.json'
if (!fs.existsSync(TABLE)) {
  console.error('[check-permission-bits][FAIL] 读不到 ' + TABLE + '（表缺失时必须失败，不能空转通过）')
  process.exit(1)
}
const rows = (JSON.parse(fs.readFileSync(TABLE, 'utf8')).rows) || []
const defined = new Set(rows.map(r => r.permission).filter(Boolean))
if (defined.size < 10) {
  console.error('[check-permission-bits][FAIL] permission 列只解析出 ' + defined.size
      + ' 个值，明显读错了字段 —— 宁可失败也不要空转')
  process.exit(1)
}

const walk = (dir, out = []) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name !== 'target') walk(full, out)
    } else if (e.name.endsWith('.java')) out.push(full)
  }
  return out
}

const used = new Map()   // 权限位 -> 出现位置
const files = walk('server')
for (const file of files) {
  const src = fs.readFileSync(file, 'utf8')
  let at = 0
  while ((at = src.indexOf('requirePermission(', at)) >= 0) {
    let depth = 0
    let i = at + 'requirePermission'.length
    for (; i < src.length; i++) {
      if (src[i] === '(') depth++
      else if (src[i] === ')') { depth--; if (depth === 0) break }
    }
    const args = src.slice(at, i + 1)
    // 最后一个字符串字面量就是权限位（前两个参数是 scope 与角色）
    const lits = [...args.matchAll(/"([A-Z_]+)"/g)].map(m => m[1])
    const bit = lits[lits.length - 1]
    if (bit && !bit.startsWith('perm_')) used.set(bit, file + ':' + (src.slice(0, at).split('\n').length))
    at = i
  }
}

const bad = [...used.keys()].filter(b => !defined.has(b)).sort()
console.log('[check-permission-bits] 表里定义了 ' + defined.size + ' 个权限位，代码引用 ' + used.size + ' 个')
if (bad.length > 0) {
  console.error('[check-permission-bits][FAIL] 这些权限位不在 role_permission.permission 列里，查表永远查不到：')
  for (const b of bad) console.error('   - ' + b + '  （' + used.get(b) + '）')
  console.error('   注意别拿行 id 顶替（行 id 形如 perm_alliance_expand，权限位是 EXPAND_CAPACITY）。')
  process.exit(1)
}
const forward = [...defined].filter(p => !used.has(p)).sort()
if (forward.length > 0) {
  console.log('[check-permission-bits] 表里配了但代码还没查（功能未落地，只报不拦）：' + forward.join(', '))
}
console.log('[check-permission-bits] 代码引用的权限位全部对得上表。')
