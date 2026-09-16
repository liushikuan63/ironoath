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
 * <p><b>换不动就算失败，不静默继续</b>：`rewritten === 0` 说明产物里那两个写死值一个字都没换，
 * 那这一轮读数全都来自另一台服务端 —— 报"一切正常"比报错更糟。
 */
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
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
  let rewritten = 0

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
    /** 只在"确实指定了非默认后端"时要求换到过东西。 */
    assertRewritten: () => {
      if (rewrite && rewritten === 0) {
        throw new Error(`指定了后端 ${backend}，但产物里那两个写死地址一个字都没换到 —— `
          + '这一轮所有读数都会打到别的机器上（默认值见本文件 BAKED_HTTP）')
      }
    },
    close: () => new Promise((resolve) => server.close(resolve)),
  }
}

export { BAKED_HTTP }
