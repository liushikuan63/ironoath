// 职责：客户端绑的每一个路径，服务端必须真的有 —— 见同名 .sh 的说明。
// 依赖：scripts/lib/endpoint-paths.js（两份清单的解析都在那里，本文件只做判定）。
const { serverPaths, clientBoundPaths } = require('./lib/endpoint-paths')

const server = new Set(serverPaths())
const client = clientBoundPaths()

const missing = client.filter(p => !server.has(p)).sort()
console.log('[check-endpoint-paths] 服务端端点 ' + server.size + ' 条，客户端绑定 ' + client.length + ' 条')
if (missing.length > 0) {
  console.error('[check-endpoint-paths][FAIL] 客户端绑了服务端不存在的路径（点了就是 404）：')
  for (const p of missing) console.error('   - ' + p)
  console.error('   先搜一遍领域层：这些能力常常是方法早就写好、控制器没挂。')
  process.exit(1)
}
console.log('[check-endpoint-paths] 客户端绑定的路径全部在服务端存在。')
