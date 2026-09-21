/**
 * 职责：`tools/lib/preview-server.mjs` 的自检——重点是 `assertRewritten()` 那两条判据
 *       **各自都能失败**，且不依赖"调用那一刻浏览器请求过哪些文件"。
 * 用法：node --test tools/lib/preview-server.test.mjs
 *
 * <p>为什么要单测这个库：全仓 33 份量具调它，判据一旦写成"看请求时机"，量具就会在第 N 次
 * 连跑时抛一句读起来像缺陷、实际是自检写错的错（2026-09-21 实测）。这里四条用例钉住行为。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const BAKED = 'http://localhost:8080'

// 每条用例一个端口，避免同一进程内前后用例抢同一个监听口。
let nextPort = 8341 + (process.pid % 40)

async function withRoot(files, backend, run, port = nextPort++) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'preview-'))
  for (const [name, text] of Object.entries(files)) {
    const dir = path.join(root, path.dirname(name))
    await import('node:fs/promises').then((fs) => fs.mkdir(dir, { recursive: true }))
    writeFileSync(path.join(root, name), text)
  }
  const { startPreviewServer } = await import('./preview-server.mjs')
  let preview
  try {
    preview = await startPreviewServer({ root, backend, port })
    return await run(preview, root)
  } finally {
    await preview?.close()
    rmSync(root, { recursive: true, force: true })
  }
}

const ARTIFACT = {
  'index.html': '<html><body><script src="index.js"></script></body></html>',
  'assets/main/index.js': `const base = "${BAKED}"; const ws = "ws://localhost:8080/ws";`,
}

test('产物里有写死地址时，一个请求都不发也能通过自检', async () => {
  await withRoot(ARTIFACT, 'http://localhost:8199', async (preview) => {
    assert.equal(preview.rewrites(), 0) // 还没服务过任何字节
    assert.doesNotThrow(() => preview.assertRewritten())
  })
})

test('产物里找不到写死地址时报错（常量过期 / 产物不是按它构建的）', async () => {
  await withRoot({ 'index.html': '<html></html>', 'a.js': 'const base = "http://localhost:9999"' },
    'http://localhost:8199', async (preview) => {
      assert.throws(() => preview.assertRewritten(), /找不到一处/)
    })
})

test('写死地址留在 html 里时报错（那份文件原样送出，不改写）', async () => {
  await withRoot({ ...ARTIFACT, 'index.html': `<script>fetch("${BAKED}/api")</script>` },
    'http://localhost:8199', async (preview) => {
      assert.throws(() => preview.assertRewritten(), /index\.html/)
    })
})

test('没指定非默认后端时两条判据都不参与（默认产物本就是打 8080 的）', async () => {
  await withRoot({ 'index.html': '<html></html>' }, BAKED, async (preview) => {
    assert.doesNotThrow(() => preview.assertRewritten())
  })
})

test('服务时确实把地址换掉了', async () => {
  await withRoot(ARTIFACT, 'http://localhost:8199', async (preview) => {
    const res = await fetch(`${preview.origin}/assets/main/index.js`)
    const text = await res.text()
    assert.ok(text.includes('http://localhost:8199'))
    assert.ok(!text.includes(BAKED))
    assert.equal(preview.rewrites(), 2)
  })
})
