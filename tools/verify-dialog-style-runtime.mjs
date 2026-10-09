/** 共享弹窗生产渲染：正常/320/240/竖屏，同页 resize、长名单末项、灰键零发送、真实拖动。 */
import fs from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { resolveCocosClickPoint } from './lib/cocos-click.mjs'
import { readDialogGeometry, scrollDialogTo, revealDialogNode } from './lib/dialog-geometry.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const OUT = process.env.DIALOG_VERIFY_OUT ?? 'tmp/dialog-style'
const PORT = Number(process.env.DIALOG_VERIFY_PORT ?? 8241)
fs.mkdirSync(OUT, { recursive: true })
console.log(`[dialog-style] 后端 ${BACKEND}`)
const deviceId = `dialog-style-${Date.now()}`
const init = await (await fetch(`${BACKEND}/player/init`, { method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ requestId: deviceId, deviceId, nickName: '弹窗验收', avatarId: 1, clientTime: Date.now() }) })).json()
if (init.code !== 0) throw new Error(`建号失败：${init.code} ${init.detail ?? init.msg}`)
const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1350 } })
await context.addInitScript(value => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = [], posts = [], results = []
page.on('pageerror', error => errors.push(error.message))
page.on('request', request => { if (request.method() === 'POST') posts.push(request.url()) })
const check = (name, pass, detail) => { results.push({ name, pass, detail }); console.log(` ${pass ? 'PASS' : 'FAIL'} ${name}${pass ? '' : ` ${JSON.stringify(detail)}`}`) }
const specs = [
  { host: 'expPick', component: 'ExpPickOverlay', footer: ['cancel', 'confirm'], disabled: 'confirm', last: '末项经验书' },
  { host: 'awakenPick', component: 'AwakenPickOverlay', footer: ['cancel', 'confirm'], disabled: 'confirm', last: '末项觉醒石' },
  { host: 'skillPick', component: 'SkillPickOverlay', footer: ['cancel', 'confirm'], disabled: 'confirm', last: '末项技能书' },
  { host: 'composePick', component: 'ComposePickOverlay', footer: ['cancel', 'confirm'], disabled: 'confirm', last: '末项合成武将' },
  { host: 'lineupEdit', component: 'LineupEditOverlay', footer: ['cancel', 'clearSlot', 'save'], disabled: 'save', last: '末项名册武将' },
  { host: 'creditsOverlay', component: 'CreditsOverlay', frame: 'CreditsCard', footer: ['CreditsCloseButton'] },
  { host: 'armyQueueDialog', owner: 'armyQueue', frame: 'armyQueueDialog', footer: ['ChoicePrev', 'ChoiceNext', 'ChoiceCancel'], disabled: 'ChoicePrev' },
  { host: 'OfflineReport', owner: 'offlineReport', frame: 'plate', footer: ['离线汇总知道了'], last: '末项社交汇总' },
  { host: 'giftPopup', component: 'GiftPopupView', frame: 'plate', footer: ['buy', 'close'], last: '末项额度说明' },
  { host: 'socialCreate', component: 'SocialCreateOverlay', footer: ['cancel', 'submit'], disabled: 'submit', last: '末项创建说明' },
  { host: 'StaminaDetail', owner: 'stamina', frame: 'Panel', footer: ['BuyButton', 'CloseButton'] },
  { host: 'MarchCompose', owner: 'marchCompose', frame: 'plate', footer: ['编成取消', '编成侦察', '编成种类', '编成出征'], last: '末项编成说明' },
]

async function renderFixture(spec) {
  const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
  const find = (root, name) => { if (root.name === name) return root; for (const child of root.children) { const hit = find(child, name); if (hit) return hit } return null }
  for (const name of ['expPick', 'awakenPick', 'skillPick', 'composePick', 'lineupEdit', 'creditsOverlay', 'giftPopup', 'OfflineReport', 'StaminaDetail', 'socialCreate', 'MarchCompose', 'armyQueueDialog']) {
    const node = find(game, name); if (node) node.active = false
  }
  for (const node of game.children) if (node.name === 'ChoiceOverlay') node.active = false
  const bootstrap = game.getComponent('GameBootstrap')
  const rows = Array.from({ length: 12 }, (_, index) => ({ itemId: `fixture-${index}`, heroId: `fixture-${index}`,
    name: index === 11 ? spec.last : `测试条目${index + 1}`, held: 1, picked: 0, usable: false,
    reason: '条件尚未满足', detailText: '材料尚未满足', slot: 'MAIN', slotText: '主技能', levelText: '1 → 2', powerText: '战力 10', note: null }))
  if (spec.component === 'ComposePickOverlay') rows[0].usable = true
  if (spec.owner) {
    if (spec.owner === 'stamina') { await bootstrap.root.openStaminaDetail(); return 'StaminaDetail' }
    const owner = bootstrap?.[spec.owner]
    if (!owner) throw new Error(`缺生产 ${spec.owner}`)
    if (spec.owner === 'armyQueue') { owner.node.name = spec.host; owner.show(Array.from({ length: 9 }, (_, index) => ({ id: `option-${index}`, label: `可选项${index + 1}`, detail: '测试选择' })), () => {}); return owner.node.name }
    if (spec.owner === 'marchCompose') { owner.render({ targetId: 'fixture-target', targetName: '验收目标', mode: 'MARCH', coordText: '1,1', notice: '末项编成说明', submitting: false,
      compose: { totalText: '0', options: Array.from({ length: 5 }, (_, index) => ({ unitId: `fixture-unit-${index}`, name: `测试兵种${index + 1}`, selected: 0, available: 10, unlocked: true })) } }); return 'MarchCompose' }
    owner.render({ items: ['资源汇总', '建筑汇总', '战斗汇总', '末项社交汇总'].map((text, index) => ({ text, detail: '点击查看', jump: ['city', 'city', 'world', 'social'][index] })) })
    return owner.node.name
  }
  const node = find(game, spec.host)
  const component = node?.getComponent(spec.component)
  if (!component) throw new Error(`缺生产组件 ${spec.component}，不能以新建假节点代替`)
  if (spec.component === 'CreditsOverlay') component.show()
  else if (spec.component === 'ExpPickOverlay') component.render({ rows, totalPicked: 0, canSend: false, emptyText: null }, '验收武将')
  else if (spec.component === 'AwakenPickOverlay') component.render({ rows, stageText: '当前 1 阶', selectedItemId: null, canSend: false, sendText: '先选觉醒石', emptyText: null }, '验收武将')
  else if (spec.component === 'SkillPickOverlay') component.render({ rows, selectedItemId: null, selectedSlot: null, canSend: false, sendText: '先选技能书', emptyText: null }, '验收武将')
  else if (spec.component === 'ComposePickOverlay') {
    window.__dialogPickIntents = 0
    component.onPick = () => { window.__dialogPickIntents++ }
    component.render({ rows, selectedHeroId: null, canSend: false, sendText: '先选武将', summaryText: '12 名武将', emptyText: null }, [])
  }
  else if (spec.component === 'LineupEditOverlay') component.render({ slots: ['main', 'sub1', 'sub2'].map(slot => ({ slot, label: slot === 'main' ? '主将' : '副将', heroId: null, heroName: null, empty: true, picking: slot === 'main' })), picks: rows, presetText: '编队 1', pickingSlot: 'main', canSave: false, saveText: '不可保存' })
  else if (spec.component === 'GiftPopupView') { component.attach({ popup: true, productId: 'fixture-gift', productName: '验收礼包', offerExpireAt: Date.now() + 60000, serverNow: Date.now() }); component.renderResult({ title: '处理结果', detail: '测试说明'.repeat(40), minorNotice: '末项额度说明' }) }
  else component.render({ titleText: '创建联盟', nameLabel: '联盟名', tagLabel: '标签', name: '', tag: '', costText: '消耗金币', hint: '末项创建说明', canSubmit: false })
  return node.name
}

try {
  await page.goto(`${preview.origin}/?panel=city`)
  await page.waitForFunction(() => window.cc?.director?.getScene()?.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('GameBootstrap')?.root != null, null, { timeout: 60000 })
  await page.waitForTimeout(2500)
  // 先验真实新号引导：使用服务器下发的当前帧，随后才暂藏，避免盖住其他弹窗的量具。
  for (const [name, viewport] of [['normal', { width: 1440, height: 1350 }], ['short320', { width: 1440, height: 480 }], ['short240', { width: 1440, height: 360 }], ['portrait', { width: 960, height: 1600 }]]) {
    await page.setViewportSize(viewport)
    await page.waitForTimeout(450)
    const guide = await page.evaluate(() => {
      const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
      const root = game.getChildByName('Guide'), component = root?.getComponent('GuideView')
      if (!component?.driver?.current()) return { error: '缺普通新号服务器引导帧' }
      component.repaint()
      const bubble = root.getChildByName('GuideBubble'), ui = bubble?.getComponent('cc.UITransform')
      const area = game.getComponent('PanelNav').contentRectFor('city')
      if (!bubble?.activeInHierarchy || !ui || !area) return { error: '缺真实GuideBubble/导航净区' }
      const box = ui.getBoundingBoxToWorld(), origin = game.getComponent('cc.UITransform')
      const point = origin.convertToNodeSpaceAR(new window.cc.Vec3(box.x, box.y, 0))
      return { width: ui.width, height: ui.height, x: point.x, y: point.y, area,
        material: !!bubble.getComponent('cc.Sprite')?.spriteFrame,
        inArea: point.x >= area.x && point.y >= area.y && point.x + ui.width <= area.x + area.width + 1 && point.y + ui.height <= area.y + area.height + 1,
        text: component.bubbleText.string }
    })
    check(`Guide/${name} 真实当前帧消费薄边材质、贴右角且在净区`, !guide.error && guide.material && guide.inArea && guide.width <= 420 && guide.x > 0, guide)
    await page.screenshot({ path: path.join(OUT, `Guide-${name}.png`) })
  }
  await hideGuideOverlay(page)
  for (const spec of specs) {
    await page.setViewportSize({ width: 1440, height: 1350 })
    await page.waitForTimeout(350)
    await page.evaluate(renderFixture, spec)
    await page.waitForTimeout(100)
    for (const [name, viewport, logicalHeight] of [['normal', { width: 1440, height: 1350 }, 900], ['short320', { width: 1440, height: 480 }, 320], ['short240', { width: 1440, height: 360 }, 240], ['portrait', { width: 960, height: 1600 }, 1600]]) {
      await page.setViewportSize(viewport)
      await page.waitForTimeout(500)
      const geometry = await page.evaluate(readDialogGeometry, spec)
      check(`${spec.host}/${name} 同页真实可视高`, Math.abs((geometry.visibleSize?.height ?? 0) - logicalHeight) < 2, geometry)
      check(`${spec.host}/${name} 材质、Mask、框与操作在净区`, !geometry.error && geometry.material && geometry.masked
        && geometry.frameInArea && geometry.clipInArea && geometry.separation && geometry.footer.every(row => row.inArea && (!row.active || row.material)), geometry)
      if (geometry.error) continue
      check(`${spec.host}/${name} 仅一个真实滚动窗且正文非空`, geometry.viewportCount === 1 && geometry.seen.length > 0, geometry)
      if (spec.host === 'composePick' && name === 'normal') {
        for (const target of ['card', 'cancel']) {
          await page.evaluate(nodeName => {
            const host = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('composePick')
            const node = host.getChildByName(nodeName)
            window.__dialogLayerRestore = { node, layer: node.layer }
            node.layer = node.layer === 1 ? 2 : 1
          }, target)
          await page.waitForTimeout(40)
          const bad = await page.evaluate(readDialogGeometry, spec)
          check(`真实${target}错层负对照判红`, target === 'card' ? bad.material === false : bad.footer.find(row => row.name === target)?.material === false, bad)
          await page.screenshot({ path: path.join(OUT, `composePick-${target}-wrong-layer.png`) })
          await page.evaluate(() => { const old = window.__dialogLayerRestore; old.node.layer = old.layer })
          await page.waitForTimeout(40)
          const restored = await page.evaluate(readDialogGeometry, spec)
          check(`真实${target}层还原复绿`, target === 'card' ? restored.material === true : restored.footer.find(row => row.name === target)?.material === true, restored)
        }
      }
      if (spec.disabled) {
        const before = posts.length
        const point = await page.evaluate(resolveCocosClickPoint, { name: spec.disabled, within: spec.host })
        check(`${spec.host}/${name} 灰键真实命中`, point.verified, point)
        if (point.verified) await page.mouse.click(point.x, point.y)
        await page.waitForTimeout(60)
        check(`${spec.host}/${name} 灰键无监听且零发送`, geometry.footer.find(row => row.name === spec.disabled)?.listening === false && posts.length === before)
      }
      let lastSeen = false
      const coverage = new Map()
      for (let offset = 0; offset <= geometry.maxOffset + Math.max(1, geometry.clip.height); offset += Math.max(1, geometry.clip.height / 2)) {
        await page.evaluate(scrollDialogTo, { ...spec, offset: Math.min(offset, geometry.maxOffset) })
        await page.waitForTimeout(32)
        const sampled = await page.evaluate(readDialogGeometry, spec)
        for (const row of sampled.seen) if (row.visibleHeight > 1) {
          const bands = coverage.get(row.key) ?? []
          bands.push([row.visibleFrom, row.visibleTo]); coverage.set(row.key, bands)
          if (spec.last && row.text.includes(spec.last) && row.visibleTo >= row.box.height - 1) lastSeen = true
        }
      }
      if (spec.last) check(`${spec.host}/${name} 末项真实滚动可见`, lastSeen)
      const uncovered = geometry.seen.filter(row => {
        const bands = (coverage.get(row.key) ?? []).sort((a, b) => a[0] - b[0]); let end = 0
        for (const band of bands) { if (band[0] > end + 1) return true; end = Math.max(end, band[1]) }
        return end < row.box.height - 1
      })
      check(`${spec.host}/${name} 全部正文文字盒完整滚动可达`, uncovered.length === 0, uncovered)
      const overflow = geometry.seen.filter(row => row.box.x < geometry.clip.x - 2
        || row.box.x + row.box.width > geometry.clip.x + geometry.clip.width + 2)
      check(`${spec.host}/${name} 正文真实宽度不溢出裁剪区`, overflow.length === 0, overflow)
      if (name === 'short320' && spec.host === 'composePick') {
        await page.evaluate(revealDialogNode, { ...spec, name: 'compose-fixture-0' })
        await page.waitForTimeout(80)
        const point = await page.evaluate(resolveCocosClickPoint, { name: 'compose-fixture-0', within: spec.host })
        check('可选行拖动坐标经引擎自命中', point.verified, point)
        const before = posts.length
        const beforeOffset = (await page.evaluate(readDialogGeometry, spec)).offset
        if (point.verified) { await page.mouse.move(point.x, point.y); await page.mouse.down(); await page.mouse.move(point.x, point.y - 45, { steps: 8 }); await page.mouse.up(); await page.waitForTimeout(250) }
        check('长名单真实拖动改变滚动位置', (await page.evaluate(readDialogGeometry, spec)).offset > beforeOffset + 10)
        check('从可选行起步拖动零选择意图与零发送', await page.evaluate(() => window.__dialogPickIntents) === 0 && posts.length === before)
        await page.evaluate(revealDialogNode, { ...spec, name: 'compose-fixture-0' })
        await page.waitForTimeout(80)
        const click = await page.evaluate(resolveCocosClickPoint, { name: 'compose-fixture-0', within: spec.host })
        if (click.verified) await page.mouse.click(click.x, click.y)
        check('可选行放开点击仍只表达一次意图', await page.evaluate(() => window.__dialogPickIntents) === 1)
      }
      if (spec.owner === 'armyQueue') {
        const reached = new Set()
        for (let index = 0; index < 3; index++) {
          for (let offset = 0; offset <= geometry.maxOffset + geometry.clip.height; offset += Math.max(1, geometry.clip.height / 2)) {
            await page.evaluate(scrollDialogTo, { ...spec, offset: Math.min(offset, geometry.maxOffset) })
            await page.waitForTimeout(32)
            const visible = await page.evaluate(readDialogGeometry, spec)
            visible.seen.filter(row => /^可选项[0-9]+$/.test(row.text) && row.visibleFrom < 1 && row.visibleTo >= row.box.height - 1)
              .forEach(row => reached.add(row.text))
          }
          if (index < 2) {
            const point = await page.evaluate(resolveCocosClickPoint, { name: 'ChoiceNext', within: spec.host })
            check(`Choice/${name} 下一页真实命中`, point.verified, point)
            if (point.verified) await page.mouse.click(point.x, point.y)
            await page.waitForTimeout(60)
          }
        }
        check(`Choice/${name} 9个选项全部分页与滚动可达`, reached.size === 9, [...reached])
        check(`Choice/${name} 页末下一页灰且无监听`, (await page.evaluate(readDialogGeometry, spec)).footer.find(row => row.name === 'ChoiceNext')?.listening === false)
        for (let index = 0; index < 2; index++) {
          const point = await page.evaluate(resolveCocosClickPoint, { name: 'ChoicePrev', within: spec.host })
          if (point.verified) await page.mouse.click(point.x, point.y)
          await page.waitForTimeout(60)
        }
      }
      await page.screenshot({ path: path.join(OUT, `${spec.host}-${name}-bottom.png`) })
      await page.evaluate(scrollDialogTo, { ...spec, offset: 0 })
      await page.waitForTimeout(80)
      await page.screenshot({ path: path.join(OUT, `${spec.host}-${name}.png`) })
    }
  }
  check('所有弹窗路径无浏览器异常', errors.length === 0, errors)
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ backend: BACKEND, errors, results }, null, 2))
} finally { await browser.close(); await preview.close() }
console.log(`共享弹窗：${results.filter(row => row.pass).length} 通过 / ${results.filter(row => !row.pass).length} 失败`)
process.exit(results.every(row => row.pass) ? 0 : 1)
