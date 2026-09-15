// 职责：把「服务端有哪些端点」「客户端绑了哪些路径」这两份清单的解析收在一处。
// 依赖：node 的 fs/path，无第三方库。
//
// 为什么要抽出来：解析规则此前只有 `check-endpoint-paths.js` 一份，而运行期量具也要问同一件事
// （"客户端一共会打多少条接口，这次实测覆盖了几条"）。复制一份解析 = 两个地方各自演化，
// 症状是量具报的覆盖率与 CI 卡口说的端点数对不上，而没人能说出哪一份是对的。
//
// 两份清单的口径各是什么：
// ① 服务端 = 扫 controller 的类前缀 + 方法级 @*Mapping（含不带路径的裸 @GetMapping）；
// ② 客户端 = 扫 GameApi 里出现的字面路径（客户端绑定的那些，玩家点得到的都在这里）。
// 反向（服务端有、客户端没绑）不判缺失 —— 那些是有意的运维/回调端点。
const fs = require('fs')
const path = require('path')

const CONTROLLER_DIR = 'server/game-web/src/main/java/com/ironoath/web/controller'
const CLIENT_API = 'client/assets/scripts/game/session/GameApi.ts'

function walkJava(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walkJava(full, out)
    else if (entry.name.endsWith('.java')) out.push(full)
  }
  return out
}

/** 服务端声明的全部 HTTP 路径。 */
function serverPaths() {
  const found = new Set()
  for (const file of walkJava(CONTROLLER_DIR)) {
    const src = fs.readFileSync(file, 'utf8')
    const pre = (src.match(/@RequestMapping\("([^"]*)"\)/) || [, ''])[1]
    for (const m of src.matchAll(/@(?:Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
      found.add(pre + m[1])
    }
    // 方法级注解不带路径（如 @GetMapping）时挂在类前缀上
    for (const m of src.matchAll(/@(?:Get|Post|Put|Delete|Patch)Mapping(?!\s*\()/g)) {
      if (pre) found.add(pre)
    }
  }
  return Array.from(found).sort()
}

/** 客户端绑定的全部路径（玩家点得到的那一些）。 */
function clientBoundPaths() {
  const api = fs.readFileSync(CLIENT_API, 'utf8')
  const found = new Set()
  for (const m of api.matchAll(/'\/[A-Za-z0-9_/-]*'/g)) found.add(m[0].slice(1, -1))
  return Array.from(found).sort()
}

module.exports = { serverPaths, clientBoundPaths }
