/**
 * 职责：把 `client/build/web-mobile` 起成一个本地静态服务，并在服务时把产物里**写死的后端地址**
 *       换成调用方指定的那台。给两个量具共用：`verify-perf-runtime.mjs`（首屏/体积/内存/弱网）
 *       与 `verify-devtools-panels.mjs`（面板渲染）。
 * 依赖：node http/fs/path。
 *
 * <p><b>为什么要抽出来</b>：这条替换规则如果存在两份，将来的改法是"其中一个跟着改"——
 * 而没改的那一份会把请求打到**别人那台**后端，症状正是本仓库踩过一次的假绿：
 * 明明指定了自己的端口，量出来的却是别人的延迟与数据。
 *
 * <p><b>为什么按"解析后的文件路径"取 content-type**：拿 `path.extname('/')` 会得到空串
 * → `application/octet-stream` → 浏览器把 index.html 当下载，`page.goto` 直接抛
 * "Download is starting"。这条注释原来只有一份，现在两处都不会再踩。
 *
 * <p><b>换不动就算失败，不静默继续</b>：产物里找不到可改写的写死值，或有写死值落在不改写的文件类型里，
 * 这一轮读数就有一部分来自另一台服务端 —— 报"一切正常"比报错更糟。两条判据都在启动扫描时算好，
 * 不看"assertRewritten() 被调用的那一刻浏览器请求过哪些文件"。
 */
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'

const BAKED_HTTP = 'http://localhost:8080'
const BAKED_WS = 'ws://localhost:8080/ws'

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json', '.css': 'text/css', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.wasm': 'application/wasm', '.svg': 'image/svg+xml',
  '.mem': 'application/octet-stream', '.data': 'application/octet-stream',
  '.ttf': 'font/ttf', '.map': 'application/json',
}

/**
 * @param root      产物目录（绝对或相对仓库根的路径）
 * @param backend   要打的后端，例如 http://localhost:8155
 * @param port      本地端口
 * @returns {Promise<{origin: string, rewrites: () => number, close: () => Promise<void>}>}
 */
export async function startPreviewServer({ root, backend, port }) {
  const rewrite = backend !== BAKED_HTTP
  const backendWs = backend.replace(/^http/, 'ws') + '/ws'
  // 启动时扫一遍产物：改写点数与"浏览器那一刻请求过哪些文件"无关，判据才不会因时机假红。
  let rewritten = 0
  let baked = 0
  let leaks = 0
  const leakFiles = []
  if (rewrite) {
    const stack = [root]
    let visited = 0
    while (stack.length > 0 && visited < 600) {
      const dir = stack.pop()
      let entries = []
      try {
        entries = readdirSync(dir, { withFileTypes: true })
      } catch {
        continue
      }
      for (const entry of entries) {
        const full = path.join(dir, entry.name)
        if (entry.isDirectory()) {
          stack.push(full)
          continue
        }
        // 只看会被浏览器当代码执行的类型：.map/.css 里的地址不会让客户端打错后端，只会造成假红
        if (!/\.(js|mjs|json|html)$/.test(entry.name)) continue
        visited += 1
        let hits = 0
        try {
          const text = readFileSync(full, 'utf8')
          hits = text.split(BAKED_HTTP).length - 1 + text.split(BAKED_WS).length - 1
        } catch {
          continue
        }
        if (hits === 0) continue
        if (/\.(js|mjs|json)$/.test(entry.name)) baked += hits
        else {
          leaks += hits
          leakFiles.push(path.relative(root, full))
        }
      }
    }
  }

  const server = createServer(async (req, res) => {
    const url = decodeURIComponent((req.url ?? '/').split('?')[0])
    const target = path.join(root, url === '/' ? 'index.html' : url)
    try {
      let buf = await readFile(target)
      if (rewrite && ['.js', '.mjs', '.json'].includes(path.extname(target))) {
        const text = buf.toString('utf8')
        const after = text.split(BAKED_HTTP).join(backend).split(BAKED_WS).join(backendWs)
        if (after !== text) {
          rewritten += text.split(BAKED_HTTP).length - 1 + text.split(BAKED_WS).length - 1
          buf = Buffer.from(after, 'utf8')
        }
      }
      res.writeHead(200, { 'content-type': MIME[path.extname(target)] ?? 'application/octet-stream' })
      res.end(buf)
      // SPA fallback 只对非文件路径生效，且不能被当成"资源存在"的证据
    } catch {
      const buf = await readFile(path.join(root, 'index.html'))
      res.writeHead(200, { 'content-type': 'text/html' }).end(buf)
    }
  })
  await new Promise((resolve) => server.listen(port, resolve))

  return {
    origin: `http://localhost:${port}`,
    rewrites: () => rewritten,
    /**
     * 两条能各自失败的判据，都不依赖"浏览器此刻请求过哪些文件"：
     * 1. 产物里数得到可改写的写死地址，否则无法证明本轮读数打在指定后端上；
     * 2. html 里一处都不许有（它原样送出、不改写），有就说明它真的还在打默认后端。
     */
    assertRewritten: () => {
      if (!rewrite) return
      if (baked === 0) {
        throw new Error(`指定了后端 ${backend}，但产物里找不到一处 ${BAKED_HTTP} —— 本服务靠替换它来改指向，`
          + '要么是 BAKED_HTTP 常量过期，要么这份产物不是按它构建的；这一轮无法确认读数打在哪个后端')
      }
      if (leaks > 0) {
        throw new Error(`产物里有 ${leaks} 处写死地址落在本服务不改写的文件类型里（${leakFiles.join('、')}）——`
          + `这些请求仍会打到 ${BAKED_HTTP}，不是 ${backend}`)
      }
    },
    close: () => new Promise((resolve) => server.close(resolve)),
  }
}

export { BAKED_HTTP }
