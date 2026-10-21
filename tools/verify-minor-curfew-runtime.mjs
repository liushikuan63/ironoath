/**
 * V19 遗留：15013 登录拒绝必须把服务端给的下次可玩时刻画给玩家。
 * 只桩版本检查与登录响应；不启动后端、不碰真实账号或年龄数据。
 * 运行前先构建本 worktree 的 web-mobile 产物。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const root = path.resolve('client/build/web-mobile')
const shotDir = path.resolve('tmp/minor-curfew')
const port = Number(process.env.MINOR_CURFEW_PROBE_PORT ?? 8368)
const nextTime = '当前不在可玩时段，下次可玩：2026-10-21 20:00'

if (!existsSync(path.join(root, 'index.html'))) {
  console.error('[minor-curfew][PREREQ] 缺 web-mobile 产物')
  process.exit(2)
}
mkdirSync(shotDir, { recursive: true })

const preview = await startPreviewServer({ root, backend: 'http://localhost:8080', port })
const browser = await chromium.launch({ headless: true })
const failures = []
const screenshots = []
try {
  for (const phase of [
    { name: 'curfew', code: 15013, msg: nextTime, viewport: { width: 1280, height: 800 } },
    // 项目横屏设计的最小支持宽度是 900px；竖屏 390px 会先被全局窄窗提示盖住。
    { name: 'curfew-landscape', code: 15013, msg: nextTime, viewport: { width: 932, height: 430 } },
    { name: 'ordinary', code: 2002, msg: '设备标识非法', viewport: { width: 1280, height: 800 } },
  ]) {
    const context = await browser.newContext({ viewport: phase.viewport })
    await context.addInitScript((name) => localStorage.setItem('ironoath.deviceId', name),
      `minor-curfew-${phase.name}-${Date.now()}`)
    const requests = []
    const pageErrors = []
    await context.route('http://localhost:8080/**', async (route) => {
      const req = route.request()
      const url = new URL(req.url())
      if (req.method() === 'OPTIONS') {
        await route.fulfill({ status: 204, headers: {
          'access-control-allow-origin': '*', 'access-control-allow-headers': '*',
          'access-control-allow-methods': 'GET,POST,OPTIONS',
        } })
        return
      }
      requests.push(url.pathname)
      await route.fulfill({ status: 200, headers: {
        'access-control-allow-origin': '*', 'content-type': 'application/json',
      }, body: JSON.stringify({
        code: url.pathname === '/player/init' ? phase.code : 9999,
        msg: url.pathname === '/player/init' ? phase.msg : '版本查询暂不可用',
        data: null, detail: null, traceId: `probe-${phase.name}`, serverNow: Date.now(),
      }) })
    })
    const page = await context.newPage()
    page.on('pageerror', error => pageErrors.push(String(error)))
    const bootLog = page.waitForEvent('console', {
      predicate: message => message.text().startsWith('[boot] '), timeout: 15_000,
    })
    await page.goto(preview.origin, { waitUntil: 'domcontentloaded' })
    await bootLog
    await page.waitForTimeout(500)
    const state = await page.evaluate(() => {
      const canvas = window.cc?.director?.getScene()?.getChildByName('Canvas')
      const notice = canvas?.getChildByName('MinorCurfewNotice') ?? null
      return {
        active: notice?.activeInHierarchy ?? false,
        text: notice?.getChildByName('Body')?.getComponent('cc.Label')?.string ?? '',
      }
    })
    const beforeTap = requests.length
    if (phase.code === 15013) {
      await page.mouse.click(860, 755)
      await page.waitForTimeout(300)
    }
    if ((requests.filter(p => p === '/player/init').length) !== 1) {
      failures.push(`${phase.name}: /player/init 请求数不是 1：${JSON.stringify(requests)}`)
    }
    if (requests.some(p => p !== '/ops/app/version' && p !== '/player/init')) {
      failures.push(`${phase.name}: 登录失败后仍发其它业务请求：${JSON.stringify(requests)}`)
    }
    if (pageErrors.length !== 0) failures.push(`${phase.name}: 页面异常 ${pageErrors[0]}`)
    if (phase.code === 15013) {
      if (!state.active || state.text !== nextTime || requests.length !== beforeTap) {
        failures.push(`${phase.name}: 提示页未原样承载或未挡住背景点击：${JSON.stringify(state)} requests=${JSON.stringify(requests)}`)
      }
    } else if (state.active) {
      failures.push(`ordinary: 普通登录失败不应误弹时段提示：${JSON.stringify(state)}`)
    }
    const shot = path.join(shotDir, `${phase.name}.png`)
    await page.screenshot({ path: shot })
    screenshots.push(shot)
    console.log(`[minor-curfew] ${phase.name} code=${phase.code} state=${JSON.stringify(state)} requests=${JSON.stringify(requests)}`)
    await context.close()
  }
} finally {
  await browser.close()
  await preview.close()
}
if (failures.length > 0) {
  for (const failure of failures) console.error(`[minor-curfew][FAIL] ${failure}`)
  process.exitCode = 1
} else {
  console.log(`[minor-curfew] PASS 3 相；截图 ${screenshots.join('、')}`)
}
