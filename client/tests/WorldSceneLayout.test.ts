import test from 'node:test'
import assert from 'node:assert/strict'
import {
  WORLD_BUTTON_HEIGHT, WORLD_NAV_INSET, worldSceneLayout,
  worldMapTranslation, worldCaptionViewport, worldMapInputAllowed,
  worldTerrainTileScale, worldFogContours,
} from '../assets/scripts/game/world/WorldSceneLayout'
import { baseCellPixels, cellPixels } from '../assets/scripts/game/world/WorldZoom'

const sizes = [[1440, 900], [1280, 720], [960, 600], [375, 667], [320, 480]] as const

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
