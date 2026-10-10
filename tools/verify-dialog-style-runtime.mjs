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
const errors = [], posts = [], allPosts = [], telemetryPosts = [], results = []
page.on('pageerror', error => errors.push(error.message))
// 周期埋点不代表行操作写业务。只单列当前后端的精确上报路径；未知路径、其它 origin 仍受零发送门约束。
const isTelemetryPost = value => {
  const url = new URL(value)
  return url.origin === new URL(BACKEND).origin && url.pathname === '/ops/track/batch'
}
page.on('request', request => {
  if (request.method() !== 'POST') return
  const url = request.url()
  allPosts.push(url)
  ;(isTelemetryPost(url) ? telemetryPosts : posts).push(url)
})
const noActionPosts = before => posts.length === before
const check = (name, pass, detail) => { results.push({ name, pass, detail }); console.log(` ${pass ? 'PASS' : 'FAIL'} ${name}${pass ? '' : ` ${JSON.stringify(detail)}`}`) }
const choiceFontEvidence = []

function readChoicePaint() {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  const owner = game?.getComponent('GameBootstrap')?.armyQueue
  const cameras = scene.getComponentsInChildren('cc.Camera')
  return (owner?.optionNodes ?? []).flatMap((node, index) => {
    if (!node.activeInHierarchy) return []
    const read = label => {
      if (!label) return null
      const ui = label.node.getComponent('cc.UITransform')
      const box = ui?.getBoundingBoxToWorld()
      return { text: label.string, fontSize: label.fontSize, actualFontSize: label.actualFontSize,
        slotHeight: ui?.height, drawable: label.node.activeInHierarchy && label.enabled
          && label.node._uiProps.uiComp === label
          && cameras.some(camera => camera.enabled && camera.node.activeInHierarchy && (camera.visibility & label.node.layer) !== 0),
        box: box ? { x: box.x, y: box.y, width: box.width, height: box.height } : null }
    }
    return [{ index, title: read(owner.optionTitleLabels[index]),
      detail: read(owner.optionDetailLabels[index]) }]
  })
}

function choiceFontIssues(rows) {
  const issues = []
  if (rows.length !== 4) issues.push('缺真实当页四个选择行')
  for (const row of rows) {
    for (const [role, expected] of [['title', 17], ['detail', 13]]) {
      const label = row[role]
      if (!label?.drawable || !label.text) issues.push(`${row.index}/${role}未真实绘制`)
      if (label?.fontSize !== expected || label?.actualFontSize !== expected) issues.push(`${row.index}/${role}实际字号未保持${expected}`)
      if (label?.slotHeight !== 27) issues.push(`${row.index}/${role}单行盒高不是27`)
    }
    const a = row.title?.box, b = row.detail?.box
    if (!a || !b || a.width <= 0 || b.width <= 0 || a.height <= 0 || b.height <= 0) issues.push(`${row.index}文字自身盒缺失`)
    else if (a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y) issues.push(`${row.index}标题与说明自身盒相交`)
  }
  return issues
}
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
    if (spec.owner === 'marchCompose') {
      window.__marchPickIntents = []
      owner.onPick = (unitId, count) => { window.__marchPickIntents.push({ unitId, count }) }
      owner.render({ targetId: 'fixture-target', targetName: '验收目标长名称'.repeat(12), mode: 'MARCH', coordText: '1,1', notice: '编成条件说明'.repeat(24) + '末项编成说明', submitting: false,
      compose: { totalText: '0', options: Array.from({ length: 5 }, (_, index) => ({ unitId: `fixture-unit-${index}`, name: `测试兵种${index + 1}`, selected: 0, available: 10, unlocked: true })) } }); return 'MarchCompose' }
    window.__offlineJumpIntents = []
    owner.onJump = jump => { window.__offlineJumpIntents.push(jump) }
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

/** 观察生产Gift正文的首渲染组件、字色和leading；截图仍需目视，组件读数不能代替字形像素验收。 */
function readGiftPaint() {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  const cameras = []
  const collectCameras = node => {
    const camera = node.getComponent('cc.Camera')
    if (camera?.enabled && node.activeInHierarchy) cameras.push(camera)
    node.children.forEach(collectCameras)
  }
  collectCameras(scene)
  const host = game?.getChildByName('giftPopup')
  const content = host?.getChildByName('DialogContent')?.getComponent('cc.ScrollView')?.content
  const rows = []
  const walk = node => {
    const label = node.getComponent('cc.Label')
    if (node.activeInHierarchy && label?.string) {
      const text = label.string
      const role = text === '限时礼包' ? 'title' : text === '验收礼包' ? 'product'
        : /^剩 \d+:\d{2}$/.test(text) ? 'countdown' : text.includes('末项额度说明') ? 'result' : 'unknown'
      const color = label.color
      rows.push({ role, text, color: [color.r, color.g, color.b, color.a],
        fontSize: label.fontSize, lineHeight: label.lineHeight,
        active: node.activeInHierarchy, enabled: label.enabled === true,
        renderer: node._uiProps.uiComp === label, layer: node.layer === host.layer,
        cameraVisible: cameras.some(camera => (camera.visibility & node.layer) !== 0) })
    }
    node.children.forEach(walk)
  }
  if (content) walk(content)
  return rows
}

const GIFT_PAINT = {
  title: { color: [184, 134, 11, 255], fontSize: 26, lineHeight: 36 },
  product: { color: [226, 214, 190, 255], fontSize: 18, lineHeight: 25 },
  countdown: { color: [150, 140, 124, 255], fontSize: 16, lineHeight: 22 },
  result: { color: [226, 214, 190, 255], fontSize: 16, lineHeight: 22 },
}
const giftPaintIssues = rows => {
  const issues = []
  const roles = rows.map(row => row.role).sort()
  if (JSON.stringify(roles) !== JSON.stringify(Object.keys(GIFT_PAINT).sort())) issues.push('四段生产正文缺失或重复')
  for (const row of rows) {
    const expected = GIFT_PAINT[row.role]
    if (!expected) { issues.push(`未知正文：${row.text}`); continue }
    if (!row.active || !row.enabled || !row.renderer || !row.layer || !row.cameraVisible) {
      issues.push(`${row.role} 首Label没有在宿主层有效渲染`)
    }
    if (JSON.stringify(row.color) !== JSON.stringify(expected.color)) issues.push(`${row.role} 字色偏离暗铁面合同：${row.color}`)
    if (row.fontSize !== expected.fontSize || row.lineHeight !== expected.lineHeight) {
      issues.push(`${row.role} 字号/leading偏离既有正文合同：${row.fontSize}/${row.lineHeight}`)
    }
  }
  return issues
}

/** 直接改生产Label与正文位置，保存真实实例后还原；不改量具回读或请求数据。 */
function mutateGiftPaint(mode) {
  if (mode === 'restore') {
    const saved = window.__giftPaintRestore
    if (!saved) return false
    saved.label.color = saved.color
    saved.label.enabled = saved.enabled
    saved.node.setPosition(saved.position)
    delete window.__giftPaintRestore
    return true
  }
  const host = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('giftPopup')
  const content = host?.getChildByName('DialogContent')?.getComponent('cc.ScrollView')?.content
  const title = content?.children.find(node => node.getComponent('cc.Label')?.string === '限时礼包')
  const node = content?.children.find(candidate => candidate.getComponent('cc.Label')?.string === '验收礼包')
  const label = node?.getComponent('cc.Label')
  if (!node || !title || !label || node._uiProps.uiComp !== label) return false
  window.__giftPaintRestore = { node, label, color: label.color.clone(), enabled: label.enabled, position: node.position.clone() }
  if (mode === 'ink') {
    const color = label.color.clone()
    color.r = 22; color.g = 18; color.b = 16
    label.color = color
  } else if (mode === 'disabled') label.enabled = false
  else node.setPosition(title.position)
  return true
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
      const plate = bubble.getChildByName('GuideBubblePlate'), sprite = plate?.getComponent('cc.Sprite')
      return { width: ui.width, height: ui.height, x: point.x, y: point.y, area,
        material: plate?.activeInHierarchy === true && plate.layer === root.layer && sprite?.enabled === true
          && plate._uiProps.uiComp === sprite && !!sprite.spriteFrame,
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
      if (spec.owner === 'armyQueue') {
        const paint = await page.evaluate(readChoicePaint)
        choiceFontEvidence.push({ phase: name, paint, issues: choiceFontIssues(paint) })
        check(`Choice/${name} 短文实际17/13字号且两行自身盒不相交`, choiceFontIssues(paint).length === 0, paint)
        if (name === 'normal') {
          try {
            await page.evaluate(() => {
              const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
              const owner = game.getComponent('GameBootstrap').armyQueue
              window.__choiceFontRestore = owner.optionNodes.flatMap((node, rowIndex) => !node.activeInHierarchy ? []
                : [owner.optionTitleLabels[rowIndex], owner.optionDetailLabels[rowIndex]].map((label, index) => {
                  const ui = label.node.getComponent('cc.UITransform')
                  const saved = { ui, width: ui.width, height: ui.height }
                  ui.setContentSize(ui.width, index === 0 ? 24 : 18)
                  return saved
                }))
            })
            await page.waitForTimeout(80)
            const negative = await page.evaluate(readChoicePaint)
            const issues = choiceFontIssues(negative)
            choiceFontEvidence.push({ phase: '旧24/18短盒负控', paint: negative, issues })
            check('Choice真实旧短盒由同字号门因实际缩字翻红', choiceFontIssues(paint).length === 0
              && negative.length === 4 && issues.some(issue => issue.includes('/title实际字号'))
              && issues.some(issue => issue.includes('/detail实际字号')), { negative, issues })
            await page.screenshot({ path: path.join(OUT, 'armyQueueDialog-font-shortbox-negative.png') })
          } finally {
            await page.evaluate(() => {
              for (const saved of window.__choiceFontRestore ?? []) saved.ui.setContentSize(saved.width, saved.height)
              delete window.__choiceFontRestore
            })
            await page.waitForTimeout(80)
            const restored = await page.evaluate(readChoicePaint)
            choiceFontEvidence.push({ phase: '同字号门还原', paint: restored, issues: choiceFontIssues(restored) })
            check('Choice还原同真实文字盒后字号门恢复绿', choiceFontIssues(restored).length === 0
              && JSON.stringify(restored) === JSON.stringify(paint), restored)
            await page.screenshot({ path: path.join(OUT, 'armyQueueDialog-font-restored.png') })
          }
        }
      }
      if (spec.host === 'MarchCompose' || spec.host === 'OfflineReport' || spec.host === 'giftPopup') {
        check(`${spec.host}/${name} 标题、坐标、正文行和说明自身框不相交`, geometry.bodyOverlaps.length === 0, geometry.bodyOverlaps)
        if (spec.host === 'MarchCompose') {
          check(`MarchCompose/${name} 条行自身框上锚与实际向下绘制一致`, geometry.bodyBoxes.filter(row => /^composeRow|^rallyBand$/.test(row.name)).every(row => row.anchorY === 1), geometry.bodyBoxes)
          check(`MarchCompose/${name} 长标题真实内宽换行`, geometry.seen.some(row => row.text.startsWith('出征：') && row.box.height > 60), geometry.seen)
        }
      }
      if (spec.host === 'giftPopup') {
        const paint = await page.evaluate(readGiftPaint)
        check(`Gift/${name} 四段正文首Label有效且暗铁面字色/leading正确`, giftPaintIssues(paint).length === 0,
          { rows: paint, issues: giftPaintIssues(paint) })
        console.log(` Gift/${name} 画色观察（对应本档截图）：${JSON.stringify(paint.map(({ role, color, fontSize, lineHeight }) => ({ role, color, fontSize, lineHeight })))}`)
        const mask = await page.evaluate(() => {
          const cc = window.cc, host = cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('giftPopup')
          const node = host.getChildByName('mask'), graphics = node.getComponent('cc.Graphics'), box = node.getComponent('cc.UITransform'), size = cc.view.getVisibleSize()
          return { active: node.activeInHierarchy, visible: node.layer === host.layer && graphics.enabled
              && node._uiProps.uiComp === graphics && graphics.fillColor.a === 190,
            full: Math.abs(box.width - size.width) < 1 && Math.abs(box.height - size.height) < 1, layer: node.layer, hostLayer: host.layer }
        })
        check(`Gift/${name} 遮罩真实绘制且全屏，避免透明层截点击`, mask.active && mask.visible && mask.full, mask)
        if (name === 'normal') {
          for (const [mode, caption] of [['ink', '旧深墨字'], ['disabled', '禁用真实首Label'], ['overlap', '商品名移到标题中心']]) {
            const baselinePaint = await page.evaluate(readGiftPaint)
            const baselineGeometry = await page.evaluate(readDialogGeometry, spec)
            const baselineClean = giftPaintIssues(baselinePaint).length === 0 && baselineGeometry.bodyOverlaps.length === 0
            let restored = false
            try {
              const planted = await page.evaluate(mutateGiftPaint, mode)
              await page.waitForTimeout(40)
              const brokenPaint = await page.evaluate(readGiftPaint)
              const brokenGeometry = await page.evaluate(readDialogGeometry, spec)
              const issues = giftPaintIssues(brokenPaint)
              const titleProductOverlap = brokenGeometry.bodyOverlaps.some(overlap =>
                (overlap.aText === '限时礼包' && overlap.bText === '验收礼包')
                || (overlap.bText === '限时礼包' && overlap.aText === '验收礼包'))
              check(`Gift ${caption}负控由同一真实判据翻红`, baselineClean && planted && (mode === 'overlap'
                ? titleProductOverlap : issues.some(issue => issue.includes(mode === 'ink' ? '字色偏离' : '首Label没有'))),
                { baselineClean, paint: brokenPaint, issues, overlaps: brokenGeometry.bodyOverlaps })
              await page.screenshot({ path: path.join(OUT, `giftPopup-${mode}-negative.png`) })
            } finally {
              restored = await page.evaluate(mutateGiftPaint, 'restore')
            }
            await page.waitForTimeout(40)
            const restoredPaint = await page.evaluate(readGiftPaint)
            const restoredGeometry = await page.evaluate(readDialogGeometry, spec)
            check(`Gift ${caption}撤桩后首Label画色与正文碰撞复绿`, restored && giftPaintIssues(restoredPaint).length === 0
              && restoredGeometry.bodyOverlaps.length === 0, { paint: restoredPaint, overlaps: restoredGeometry.bodyOverlaps })
          }
        }
      }
      if (spec.host === 'composePick' && name === 'normal') {
        for (const target of ['card', 'cancel']) {
          await page.evaluate(nodeName => {
            const host = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('composePick')
            const parent = host.getChildByName(nodeName)
            const node = parent.getChildByName(nodeName === 'card' ? 'DialogFrameArt' : 'DialogButtonArt') ?? parent
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
        check(`${spec.host}/${name} 灰键无监听且零发送`, geometry.footer.find(row => row.name === spec.disabled)?.listening === false && noActionPosts(before), posts.slice(before))
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
        check('从可选行起步拖动零选择意图与零发送', await page.evaluate(() => window.__dialogPickIntents) === 0 && noActionPosts(before), posts.slice(before))
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
      if (name === 'short240' && (spec.host === 'MarchCompose' || spec.host === 'OfflineReport')) {
        const before = posts.length
        const count = spec.host === 'MarchCompose' ? 5 : 4
        for (let index = 0; index < count; index++) {
          const rowName = spec.host === 'MarchCompose' ? `composeRow${index}` : `offlineRow${index}`
          await page.evaluate(revealDialogNode, { ...spec, name: rowName })
          await page.waitForTimeout(80)
          const point = await page.evaluate(resolveCocosClickPoint,
            spec.host === 'MarchCompose' ? { name: 'row-＋', within: rowName } : { name: rowName, within: spec.host })
          check(`${spec.host}/short240 第${index + 1}行实际动作真实命中`, point.verified, point)
          if (point.verified) await page.mouse.click(point.x, point.y)
          await page.waitForTimeout(60)
        }
        const intents = await page.evaluate(host => host === 'MarchCompose' ? window.__marchPickIntents : window.__offlineJumpIntents, spec.host)
        check(`${spec.host}/short240 所有行真实点击仅表达一次意图`, intents.length === count
          && (spec.host !== 'MarchCompose' || new Set(intents.map(row => row.unitId)).size === 5), intents)
        check(`${spec.host}/short240 行动作未直接发送请求`, noActionPosts(before), posts.slice(before))
      }
      await page.screenshot({ path: path.join(OUT, `${spec.host}-${name}-bottom.png`) })
      await page.evaluate(scrollDialogTo, { ...spec, offset: 0 })
      await page.waitForTimeout(80)
      await page.screenshot({ path: path.join(OUT, `${spec.host}-${name}.png`) })
    }
    if (spec.host === 'giftPopup') {
      const close = await page.evaluate(resolveCocosClickPoint, { name: 'close', within: 'giftPopup' })
      check('Gift真实关闭坐标经引擎自命中', close.verified, close)
      if (close.verified) await page.mouse.click(close.x, close.y)
      await page.waitForTimeout(80)
      check('Gift真实关闭后整层退场释放点击', await page.evaluate(() => window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('giftPopup').active === false))
    }
  }
  check('仅当前后端精确track/batch属于遥测', isTelemetryPost(`${BACKEND}/ops/track/batch`)
    && !isTelemetryPost(`${BACKEND}/ops/track/batch-extra`)
    && !isTelemetryPost('https://telemetry-negative.invalid/ops/track/batch'))
  // 真实页面发 POST，路由在浏览器截住避免改玩家数据；同一零业务发送判据必须由绿转红。
  for (const endpoint of ['/world/march', '/unknown-dialog-negative']) {
    const url = `${BACKEND}${endpoint}`
    const before = posts.length
    check(`${endpoint}负控前零业务发送基线`, noActionPosts(before))
    await page.route(url, route => route.fulfill({ status: 200, contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type', 'access-control-allow-methods': 'POST, OPTIONS' },
      body: '{"code":9000,"msg":"量具负控"}' }))
    try {
      await page.evaluate(async url => { await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }) }, url)
      check(`${endpoint}真实POST由同一零发送判据翻红`, !noActionPosts(before) && posts.slice(before).includes(url), posts.slice(before))
    } finally { await page.unroute(url) }
  }
  check('所有弹窗路径无浏览器异常', errors.length === 0, errors)
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ backend: BACKEND, errors, results,
    requests: { allPosts, businessPosts: posts, telemetryPosts } }, null, 2))
  fs.writeFileSync(path.join(OUT, 'choice-font-evidence.json'), JSON.stringify(choiceFontEvidence, null, 2))
} finally { await browser.close(); await preview.close() }
console.log(`共享弹窗：${results.filter(row => row.pass).length} 通过 / ${results.filter(row => !row.pass).length} 失败`)
process.exit(results.every(row => row.pass) ? 0 : 1)
