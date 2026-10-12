/** 真实构建/鼠标入口验收。军队读口与派遣拒绝为显式夹具，不宣称服务端采集闭环。 */
import fs from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { clickNodeViaCocos } from './lib/cocos-click.mjs'

const backend = process.env.GATHER_ENTRY_BACKEND
if (!backend) { console.error('缺 GATHER_ENTRY_BACKEND，禁止静默回退'); process.exit(2) }
const out = process.env.GATHER_ENTRY_OUT ?? 'client/build/gather-entry-verify'
fs.mkdirSync(out, { recursive: true })
const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend,
  port: Number(process.env.GATHER_ENTRY_PORT ?? 9563) })
console.log(`=== 资源采集入口：产物 ${preview.origin}，后端 ${backend}；军队/派遣显式夹具 ===`)
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 592 } })
await page.addInitScript(id => localStorage.setItem('ironoath.deviceId', id), `gather-entry-${Date.now()}`)
const requests = [], errors = [], clicks = []
let pass = 0, fail = 0, armyFixtureUsed = false
const check = (name, value) => { if (value) { pass++; console.log('PASS ' + name) }
  else { fail++; console.log('FAIL ' + name) } }
page.on('pageerror', error => errors.push(error.message))
await page.route('**/army/list', async route => {
  const response = await route.fetch(), body = await response.json()
  if (body.code !== 0 || !body.data?.units?.length) throw Error('真实军队读口未就绪')
  body.data.units[0].count = 100
  body.data.units[0].unlocked = true
  body.data.units[0].unlockHint = null
  armyFixtureUsed = true
  await route.fulfill({ response, json: body })
})
await page.route('**/world/march', async route => {
  const body = route.request().postDataJSON()
  requests.push({ method: route.request().method(), toX: body.toX, toY: body.toY,
    action: body.action, units: body.units, heroes: body.heroes, hasRequestId: !!body.requestId })
  await route.fulfill({ status: 200, json: { code: 1006,
    msg: '验收夹具已捕获采集请求，未实际发兵', data: null, serverNow: Date.now() } })
})
const tap = async (name, within) => {
  const result = await clickNodeViaCocos(page, { name, within })
  clicks.push({ name, within, ...result })
  if (!result.clicked) throw Error('实际命中失败 ' + name + ' ' + result.reason)
  await page.waitForTimeout(300)
}
const snapshot = () => page.evaluate(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
  const world = game.getChildByName('world').getComponent('WorldMap')
  const overlay = game.getChildByName('MarchCompose'), nodes = []
  const walk = node => { if (!node.activeInHierarchy) return
    const label = node.getComponent('cc.Label')
    nodes.push({ name: node.name, parent: node.parent?.name, text: label?.string ?? '' })
    node.children.forEach(walk) }
  walk(game)
  const target = game.getComponent('GameBootstrap').root.composeTarget
  const center = world.node.worldPosition
  const resources = [...world.drawnEntities].filter(([key, node]) => key.startsWith('RESOURCE:') && node.activeInHierarchy)
    .map(([key, node]) => {
      const icon = world.refs.get(node)?.spriteNode.activeInHierarchy ? world.refs.get(node).spriteNode : node
      return { key, uuid: icon.uuid, distance: Math.hypot(icon.worldPosition.x - center.x, icon.worldPosition.y - center.y) }
    }).sort((a, b) => a.distance - b.distance)
  return { selectedKey: world.selectedKey, overlayActive: overlay.activeInHierarchy,
    resources, target: target ? { x: target.x, y: target.y, name: target.name, kind: target.kind } : null, nodes }
})
try {
  await page.goto(preview.origin, { waitUntil: 'networkidle' })
  preview.assertRewritten()
  await page.waitForFunction(() => window.cc?.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('GameBootstrap')?.root?.playerId)
  await hideGuideOverlay(page)
  await tap('Nav-world')
  await page.waitForTimeout(1800)
  await tap('ZoomInButton', 'world')
  let selected = false
  for (const resource of (await snapshot()).resources.slice(0, 30)) {
    const point = await clickNodeViaCocos(page, { uuid: resource.uuid, within: 'world' })
    clicks.push({ resourceKey: resource.key, ...point })
    if (!point.clicked) continue
    await page.waitForTimeout(300)
    if ((await snapshot()).selectedKey === resource.key) { selected = true; break }
  }
  if (!selected) throw Error('当前探索视野中未命中真实资源点，不能宣称入口已验')
  const before = await snapshot()
  check('真实资源选中后按钮变为采集资源', before.nodes.some(n => n.name === 'MarchButton_Caption' && n.text === '采集资源'))
  await tap('MarchButton', 'world')
  const compose = await snapshot()
  check('真实地图按钮经装配打开编成', compose.overlayActive)
  check('采集不展示玩家城集结/侦察按钮', !compose.nodes.some(n => ['编成种类', '编成侦察'].includes(n.name)))
  check('确认文字为采集', compose.nodes.some(n => n.parent === '编成出征' && n.text === '采集'))
  check('默认未勾全军且尚无派遣请求', requests.length === 0 && compose.nodes.some(n => n.parent === 'composeRow0' && n.text === '0 / 100'))
  await page.screenshot({ path: path.join(out, 'gather-compose.png') })
  await tap('编成取消', 'MarchCompose')
  check('取消收起且零派遣', !(await snapshot()).overlayActive && requests.length === 0)
  await tap('MarchButton', 'world')
  await tap('row-＋', 'MarchCompose')
  await tap('编成出征', 'MarchCompose')
  await page.waitForTimeout(500)
  check('确认一次发送实际GATHER/所选10兵且含幂等键', requests.length === 1
    && requests[0].method === 'POST' && requests[0].action === 'GATHER' && requests[0].hasRequestId
    && requests[0].toX === compose.target?.x && requests[0].toY === compose.target?.y
    && requests[0].units.length === 1 && requests[0].units[0].count === 10 && requests[0].heroes.length === 0)
  const rejected = await snapshot()
  check('夹具拒绝保留编成及原因，不伪造发兵成功', rejected.overlayActive
    && rejected.nodes.some(n => n.text.includes('验收夹具已捕获采集请求')))
  check('军队夹具确实执行', armyFixtureUsed)
  check('页面零未捕获异常', errors.length === 0)
  fs.writeFileSync(path.join(out, 'actual.json'), JSON.stringify({ accepted: fail === 0,
    presentationContractOnly: true, serviceGatherVerified: false, nativeVerified: false,
    fixtures: ['army/list首兵种100可用', 'world/march拒绝回执，不转发业务写入', '引导遮罩隐藏'],
    pass, fail, requests, errors, clicks, before, compose, rejected }, null, 2) + '\n')
} catch (error) { fail++; console.error(String(error))
  const state = await snapshot().catch(() => null)
  await page.screenshot({ path: path.join(out, 'first-failure.png') }).catch(() => {})
  fs.writeFileSync(path.join(out, 'first-failure.json'), JSON.stringify({ accepted: false,
    error: String(error), pass, fail, requests, errors, clicks, state }, null, 2) + '\n')
} finally { await browser.close(); await preview.close() }
console.log(`资源入口结果：${pass}通过/${fail}失败；不代表服务端或原生采集闭环`)
process.exitCode = fail ? 1 : 0
