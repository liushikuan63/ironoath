// 职责：客户端绑的每一个路径，服务端必须真的有 —— 见同名 .sh 的说明。
// 依赖：scripts/lib/endpoint-paths.js（两份清单的解析都在那里，本文件只做判定）。
const { serverPaths, clientBoundPaths } = require('./lib/endpoint-paths')

/* 可选参数：换一份「服务端 Java 根目录」与「客户端 GameApi 文件」跑
   （用来验证这条检查真的会红，而不必改仓库里的控制器/客户端）。
   ⚠️ **不传参数时行为与改动前完全一致**（模式隔离）。
   写法与 scripts/check-config-refs.js / check-contract-defs.js 的同名口一致。 */
const server = new Set(serverPaths(process.argv[2]))
const client = clientBoundPaths(process.argv[3])

const missing = client.filter(p => !server.has(p)).sort()
console.log('[check-endpoint-paths] 服务端端点 ' + server.size + ' 条，客户端绑定 ' + client.length + ' 条')
if (missing.length > 0) {
  console.error('[check-endpoint-paths][FAIL] 客户端绑了服务端不存在的路径（点了就是 404）：')
  for (const p of missing) console.error('   - ' + p)
  console.error('   先搜一遍领域层：这些能力常常是方法早就写好、控制器没挂。')
  process.exit(1)
}
console.log('[check-endpoint-paths] 客户端绑定的路径全部在服务端存在。')
