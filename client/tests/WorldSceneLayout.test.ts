import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import {
  WORLD_BUTTON_HEIGHT, WORLD_NAV_INSET, worldSceneLayout,
  worldMapTranslation, worldCaptionViewport, worldMapInputAllowed,
  worldTerrainTileScale, worldFogContours,
} from '../assets/scripts/game/world/WorldSceneLayout'
import { baseCellPixels, cellPixels } from '../assets/scripts/game/world/WorldZoom'
import type { ViewportInsets } from '../assets/scripts/game/ui/DialogLayout'
import { buildMarchPanel } from '../assets/scripts/game/world/MarchPanel'
import type { MarchRender } from '../assets/scripts/game/world/WorldViewModel'

const sizes = [[1440, 900], [1280, 720], [960, 600], [375, 667], [320, 480]] as const

test('队伍已经到达后重新打开行军面板要拉权威状态，关闭不重查', () => {
  const client = existsSync(path.resolve('client/assets')) ? path.resolve('client') : process.cwd()
  const source = readFileSync(path.join(client, 'assets/scripts/scene/WorldMap.ts'), 'utf8')
  const ast = ts.createSourceFile('WorldMap.ts', source, ts.ScriptTarget.ES2020, true)
  const body = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast,
    ast.statements.filter(statement => !ts.isImportDeclaration(statement))))
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2020,
    module: ts.ModuleKind.CommonJS, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}
  let visible = false, requests = 0
  let march = { action: 'GATHER', status: 'MARCHING', marchId: 'arrived-gather',
    to: { x: 400, y: 269 }, x: 400, y: 269, load: 0, loadCap: 5320,
    remainingMs: 0, gatherRemainingMs: 0 } as MarchRender
  let rows: ReturnType<typeof buildMarchPanel> = []
  type Scene = { toggleMarchPanel(): void; renderMarchPanel(marches: MarchRender[]): void;
    marchPanel: unknown; resetGesture(): void }
  let scene: Scene
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass },
    Component: class {}, Color: class {}, worldSceneLayout, buildMarchPanel,
    worldRequester: () => ({ marches: () => {
      requests++
      march = { ...march, status: 'GATHERING' }
      scene.renderMarchPanel([march])
    } }) })
  const WorldMap = exports.WorldMap as new () => Scene
  scene = new WorldMap()
  scene.resetGesture = () => {}
  scene.marchPanel = { get isVisible() { return visible }, toggle: () => { visible = !visible },
    render: (next: ReturnType<typeof buildMarchPanel>) => { rows = next } }
  scene.toggleMarchPanel()
  assert.equal(requests, 1, '打开面板必须重新读取到达后的服务端状态')
  assert.equal(rows[0]?.actionText, '收取', '不能继续显示出发时的召回按钮')
  scene.toggleMarchPanel()
  assert.equal(requests, 1, '关闭面板不需要额外查询')
})

test('R18顶部安全区不能遮住地图工具栏，地图底边也避开安全底边上的导航', () => {
  const width = 960, height = 443.6018957345971
  const safe = { left: 0, right: 0, top: 58.047393364928894, bottom: 12 }
  const layout = worldSceneLayout(width, height, safe)
  for (const button of layout.buttons) {
    assert.ok(button.y + WORLD_BUTTON_HEIGHT / 2 <= height / 2 - safe.top,
      `${button.name}进入顶部安全区，玩家触摸被GuideMask吞掉`)
  }
  assert.equal(layout.mapBottom, -height / 2 + safe.bottom + WORLD_NAV_INSET)
  assert.equal(worldMapInputAllowed(layout, safe.bottom + WORLD_NAV_INSET - 0.1), false)
  assert.equal(worldMapInputAllowed(layout, safe.bottom + WORLD_NAV_INSET), true)
  assert.equal(worldMapInputAllowed(layout, height - safe.top - 1), false)
})

test('非对称左右安全区内按钮换行，地图投影与名牌仍使用同一净区', () => {
  const width = 375, height = 667
  const safe = { left: 40, right: 12, top: 46, bottom: 24 }
  const layout = worldSceneLayout(width, height, safe)
  for (const button of layout.buttons) {
    assert.ok(button.x - button.width / 2 >= -width / 2 + safe.left)
    assert.ok(button.x + button.width / 2 <= width / 2 - safe.right)
    assert.ok(button.y + WORLD_BUTTON_HEIGHT / 2 <= height / 2 - safe.top)
    assert.ok(button.y - WORLD_BUTTON_HEIGHT / 2 > layout.statusY + 14)
  }
  const translation = worldMapTranslation(layout, 120.75, 240.25, 18)
  const viewport = worldCaptionViewport(layout, 120.75, 240.25, 18)
  assert.equal(viewport.minX + translation.x, -width / 2 + safe.left)
  assert.equal(viewport.maxX + translation.x, width / 2 - safe.right)
  assert.equal(viewport.minY + translation.y, layout.mapBottom)
  assert.equal(viewport.maxY + translation.y, layout.mapTop)
  assert.equal((120.75 + 0.5) * 18 + translation.x, (safe.left - safe.right) / 2)
  const uiY = layout.mapCenterY + height / 2
  assert.equal(worldMapInputAllowed(layout, uiY, safe.left - 0.1), false)
  assert.equal(worldMapInputAllowed(layout, uiY, safe.left), true)
  assert.equal(worldMapInputAllowed(layout, uiY, width - safe.right - 0.1), true)
  assert.equal(worldMapInputAllowed(layout, uiY, width - safe.right), false)
})

test('真实WorldMap初次加载与同尺寸安全区变化都更新布局，稳定帧复用工具栏', () => {
  // 执行实际场景方法，节点桩只验证接线与重排，不替代Cocos实画/原生触摸。
  const client = existsSync(path.resolve('client/assets')) ? path.resolve('client') : process.cwd()
  const source = readFileSync(path.join(client, 'assets/scripts/scene/WorldMap.ts'), 'utf8')
  const ast = ts.createSourceFile('WorldMap.ts', source, ts.ScriptTarget.ES2020, true)
  const body = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast,
    ast.statements.filter(statement => !ts.isImportDeclaration(statement))))
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2020,
    module: ts.ModuleKind.CommonJS, experimentalDecorators: true } }).outputText
  class Transform { setContentSize(): void {} }
  class SceneNode {
    layer = 1
    addChild(): void {}
    addComponent(): Transform { return new Transform() }
    getComponent(): Transform { return new Transform() }
    getChildByName(): null { return null }
  }
  let safe: ViewportInsets = { left: 0, right: 0, top: 58.047393364928894, bottom: 12 }
  const size = { width: 960, height: 443.6018957345971 }
  const exports: Record<string, unknown> = {}
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass },
    Component: class { node = new SceneNode() }, Color: class {}, Node: SceneNode,
    UITransform: Transform, Graphics: class {}, NodePool: class {},
    worldSceneLayout, view: { getVisibleSize: () => size }, viewportSafeInsets: () => safe })
  type Scene = { onLoad(): void; syncViewport(): void; sceneLayout: ReturnType<typeof worldSceneLayout>;
    buildBackground(): void; buildLayer(): SceneNode; buildHud(): void; buildMarchPanel(): void;
    bindInput(): void; bindModel(): void; createOverlayGraphics(): SceneNode }
  const WorldMap = exports.WorldMap as new () => Scene
  const scene = new WorldMap()
  let builds = 0
  scene.buildBackground = () => {}
  scene.buildLayer = () => new SceneNode()
  scene.buildHud = () => { builds++ }
  scene.buildMarchPanel = () => {}
  scene.bindInput = () => {}
  scene.bindModel = () => {}
  scene.createOverlayGraphics = () => new SceneNode()
  scene.onLoad()
  assert.ok(scene.sceneLayout.buttons[0]!.y + WORLD_BUTTON_HEIGHT / 2 <= size.height / 2 - safe.top)
  scene.syncViewport()
  assert.equal(builds, 1)
  const firstY = scene.sceneLayout.buttons[0]!.y
  safe = { ...safe, top: safe.top + 16 }
  scene.syncViewport()
  assert.equal(builds, 2, '同尺寸的胶囊安全区变化必须重排工具栏')
  assert.equal(scene.sceneLayout.buttons[0]!.y, firstY - 16)
  scene.syncViewport()
  assert.equal(builds, 2, '稳定安全区不能每帧销毁工具栏')
})

test('操作条在横竖画布都不出框、不重叠，坐标状态行在全部按钮下方', () => {
  for (const [w, h] of sizes) {
    const layout = worldSceneLayout(w, h)
    assert.equal(layout.buttons.length, 5)
    assert.ok(layout.mapHeight > 0)
    for (const button of layout.buttons) {
      assert.ok(button.x - button.width / 2 >= -w / 2)
      assert.ok(button.x + button.width / 2 <= w / 2)
      assert.ok(button.y - WORLD_BUTTON_HEIGHT / 2 > layout.statusY + 14)
    }
    for (let i = 0; i < layout.buttons.length; i++) {
      for (const b of layout.buttons.slice(i + 1)) {
        const a = layout.buttons[i]!
        assert.ok(Math.abs(a.x - b.x) >= (a.width + b.width) / 2
          || Math.abs(a.y - b.y) >= WORLD_BUTTON_HEIGHT)
      }
    }
  }
  assert.ok(worldSceneLayout(320, 480).hudHeight > worldSceneLayout(960, 600).hudHeight)
})

test('两档相机投影及其逆变换保持同一世界格，回城焦点在净区中心', () => {
  for (const [w, h] of sizes) {
    const layout = worldSceneLayout(w, h)
    for (const zoom of [0, 1]) {
      const cell = cellPixels(zoom, baseCellPixels(w, layout.mapHeight, 32))
      for (const [cameraX, cameraY] of [[17, 23], [500.35, 600.6], [0, 0]]) {
        const translation = worldMapTranslation(layout, cameraX!, cameraY!, cell)
        for (const [x, y] of [[cameraX!, cameraY!], [cameraX! + 4, cameraY! - 3]]) {
          const screenX = (x! + 0.5) * cell + translation.x
          const screenY = (y! + 0.5) * cell + translation.y
          assert.ok(Math.abs((screenX - translation.x) / cell - 0.5 - x!) < 1e-9)
          assert.ok(Math.abs((screenY - translation.y) / cell - 0.5 - y!) < 1e-9)
          if (x === cameraX && y === cameraY) {
            assert.ok(Math.abs(screenX) < 1e-9)
            assert.ok(Math.abs(screenY - layout.mapCenterY) < 1e-9)
          }
        }
      }
    }
  }
})

test('名牌净区随相机和视口变动，与地图层变换逐边一致', () => {
  for (const [w, h] of sizes) {
    const layout = worldSceneLayout(w, h)
    const translation = worldMapTranslation(layout, 120.75, 240.25, 18)
    const viewport = worldCaptionViewport(layout, 120.75, 240.25, 18)
    assert.equal(viewport.minX + translation.x, -w / 2)
    assert.equal(viewport.maxX + translation.x, w / 2)
    assert.equal(viewport.minY + translation.y, layout.mapBottom)
    assert.equal(viewport.maxY + translation.y, layout.mapTop)
  }
})

test('只有地图净区启动拖动，顶栏与底导航均拒绝边界事件', () => {
  for (const [w, h] of sizes) {
    const layout = worldSceneLayout(w, h)
    assert.equal(worldMapInputAllowed(layout, WORLD_NAV_INSET - 0.1), false)
    assert.equal(worldMapInputAllowed(layout, WORLD_NAV_INSET), true)
    assert.equal(worldMapInputAllowed(layout, h - layout.hudHeight - 0.1), true)
    assert.equal(worldMapInputAllowed(layout, h - layout.hudHeight), false)
    assert.equal(worldMapInputAllowed(layout, h), false)
  }
})

test('3×3地图视野仍覆盖净区长边，缩放尺寸随实际净区而非旧全屏高度计算', () => {
  for (const [w, h] of sizes) {
    const layout = worldSceneLayout(w, h)
    const extent = 3 * 32 * baseCellPixels(w, layout.mapHeight, 32)
    assert.ok(extent >= Math.max(w, layout.mapHeight) - 0.5)
  }
})

test('地形裁帧的重复密度随投影缩放，图层覆盖面积及世界格保持原值', () => {
  for (const [w, h] of sizes) {
    const layout = worldSceneLayout(w, h)
    for (const zoom of [0, 1]) {
      const chunkPixels = 32 * cellPixels(zoom, baseCellPixels(w, layout.mapHeight, 32))
      for (const framePixels of [64, 256]) {
        const scale = worldTerrainTileScale(chunkPixels, framePixels)
        const nodePixels = chunkPixels / scale
        assert.ok(nodePixels / framePixels <= 2 + 1e-9)
        assert.ok(Math.abs(nodePixels * scale - chunkPixels) < 1e-9)
      }
    }
  }
  assert.equal(worldTerrainTileScale(1440, 64) / worldTerrainTileScale(480, 64), 3)
})

test('迷雾纹理不按九块重复：固定轮廓数、无时间输入、宽高变化仅缩放几何', () => {
  const contours = worldFogContours(1440, 900)
  assert.deepEqual(worldFogContours(1440, 900), contours)
  assert.ok(contours.length > 0 && contours.length <= 24)
  const portrait = worldFogContours(375, 667)
  for (let index = 0; index < contours.length; index++) {
    const contour = contours[index]!
    assert.ok(contour.opacity <= 12)
    assert.ok(contour.points.length <= 32)
    for (let point = 0; point < contour.points.length; point++) {
      const a = contour.points[point]!
      const b = portrait[index]!.points[point]!
      assert.ok(Number.isFinite(a.x) && Number.isFinite(a.y))
      assert.ok(Math.abs(a.x / 1440 - b.x / 375) < 1e-9)
      assert.ok(Math.abs(a.y / 900 - b.y / 667) < 1e-9)
    }
  }
})
