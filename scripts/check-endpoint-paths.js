// 职责：客户端绑的每一个路径，服务端必须真的有。见 check-endpoint-paths.sh 的说明。
// 依赖：node。拆成单独文件是因为正则里全是引号，塞进 shell 的 -e 参数会写成一堆转义噪音。
const fs = require('fs')
const path = require('path')

const walk = (dir, out = []) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else if (entry.name.endsWith('.java')) out.push(full)
  }
  return out
}

const server = new Set()
for (const file of walk('server/game-web/src/main/java/com/ironoath/web/controller')) {
  const src = fs.readFileSync(file, 'utf8')
  const pre = (src.match(/@RequestMapping\("([^"]*)"\)/) || [, ''])[1]
  for (const m of src.matchAll(/@(?:Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
    server.add(pre + m[1])
  }
  // 方法级注解不带路径（如 @GetMapping）时挂在类前缀上
  for (const m of src.matchAll(/@(?:Get|Post|Put|Delete|Patch)Mapping(?!\s*\()/g)) {
    if (pre) server.add(pre)
  }
}

const api = fs.readFileSync('client/assets/scripts/game/session/GameApi.ts', 'utf8')
const client = new Set()
for (const m of api.matchAll(/'\/[A-Za-z0-9_/-]*'/g)) client.add(m[0].slice(1, -1))

const missing = [...client].filter(p => !server.has(p)).sort()
console.log('[check-endpoint-paths] 服务端端点 ' + server.size + ' 条，客户端绑定 ' + client.size + ' 条')
if (missing.length > 0) {
  console.error('[check-endpoint-paths][FAIL] 客户端绑了服务端不存在的路径（点了就是 404）：')
  for (const p of missing) console.error('   - ' + p)
  console.error('   先搜一遍领域层：这些能力常常是方法早就写好、控制器没挂。')
  process.exit(1)
}
console.log('[check-endpoint-paths] 客户端绑定的路径全部在服务端存在。')
