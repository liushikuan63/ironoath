/**
 * 职责：钉住"36 个锚点 → 面板落点"的投影，让"退回均匀棋盘"这件事**会红**。
 * 依赖：node:test / node:assert；只读引擎无关的 `CitySceneAnchors`。
 *
 * <p>为什么这条判据只能放在这里：视图照抄投影，所以观感对不对 = 投影对不对。
 * 规格 §3.3 的原话是"不允许把 36 个锚点仍均匀排成棋盘，再称为完成城景化"——
 * 那是一句**可以被算出来**的话，不必靠人看截图。
 *
 * <p>每条都值得红：
 * ① 出框 ⇒ 建筑画到卡片九宫格的角饰底下，玩家看不见它但点得到；
 * ② 重叠 ⇒ 两栋楼叠在一起，其中一栋"凭空消失"；
 * ③ 行列间距全相等 ⇒ 就是棋盘，本格的立论被推翻；
 * ④ 两轴缩放不等 ⇒ 城被拉扁，屋顶全变椭圆；
 * ⑤ 格数/格位与锚点表不符 ⇒ 有格没有落点，那格上的建筑不画。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  SCENE_ANCHORS, SCENE_GRID_WIDTH, SCENE_GRID_HEIGHT, projectSceneLayout,
  SCENE_RUNTIME_WIDTH, SCENE_RUNTIME_HEIGHT, coverSceneSize, fitSceneLandmark, scenePlatesBackToFront,
} from '../assets/scripts/game/city/CitySceneAnchors'
import { buildingArtFootRatio } from '../assets/scripts/game/art/ArtFamilies'
import { safeAreaInsets } from '../assets/scripts/game/ui/DialogLayout'

/** 与 `CityPanelView` 同一组真源，避免视图变大而测试仍拿旧棋盘尺寸判绿。 */
const AREA_WIDTH = SCENE_RUNTIME_WIDTH
const AREA_HEIGHT = SCENE_RUNTIME_HEIGHT
const PADDING = 6
const layout = projectSceneLayout(AREA_WIDTH, AREA_HEIGHT, PADDING)

const plateAt = (gridX: number, gridY: number) => {
  const plate = layout.plates.find((p) => p.gridX === gridX && p.gridY === gridY)
  assert.ok(plate !== undefined, `第 ${gridY} 行第 ${gridX} 列没有投影落点`)
  return plate
}

test('1.5 比例地形在 1.6 视口中按原比覆盖，两端裁切而不是压扁', () => {
  const size = coverSceneSize(960, 600, 1536, 1024)
  assert.equal(size.width, 960)
  assert.equal(size.height, 640)
  assert.equal((size.height - 600) / 2, 20, '舞台居中时上下各裁 20 设计像素')
  assert.equal(size.width / size.height, 1536 / 1024)
})

test('地形 cover 在横屏和竖屏均保比例、无露底，至少一边恰好贴合视口', () => {
  const viewports = [[1440, 900], [960, 600], [480, 320], [600, 960]] as const
  for (const [width, height] of viewports) {
    const size = coverSceneSize(width, height, 1536, 1024)
    assert.ok(size.width >= width && size.height >= height, 'cover 不能露出底图之外的背景')
    assert.ok(Math.abs(size.width / size.height - 1.5) < 1e-9, 'cover 必须保持原图比例')
    assert.ok(Math.abs(size.width - width) < 1e-9 || Math.abs(size.height - height) < 1e-9,
      '至少一边贴合视口，不能无故额外放大并裁掉地形')
  }
})

test('36 个锚点全部投影出来，格位与锚点表一一对上', () => {
  assert.equal(layout.plates.length, SCENE_GRID_WIDTH * SCENE_GRID_HEIGHT)
  assert.equal(layout.plates.length, SCENE_ANCHORS.length, '有锚点没被投影')
  for (const anchor of SCENE_ANCHORS) {
    const plate = plateAt(anchor.gridX, anchor.gridY)
    assert.equal(plate.district, anchor.district)
    assert.ok(Number.isFinite(plate.x) && Number.isFinite(plate.y),
      `${anchor.anchorId} 投影出了 NaN —— 面积或锚点表被算坏了`)
  }
})

test('每一格地皮都完整落在内容框内（不压到卡片角饰下面）', () => {
  const halfW = AREA_WIDTH / 2 - PADDING
  const halfH = AREA_HEIGHT / 2 - PADDING
  for (const plate of layout.plates) {
    assert.ok(Math.abs(plate.x) + plate.width / 2 <= halfW + 0.5,
      `p${plate.gridY}${plate.gridX} 横向出框：${(Math.abs(plate.x) + plate.width / 2 - halfW).toFixed(1)}px`)
    assert.ok(Math.abs(plate.y) + plate.height / 2 <= halfH + 0.5,
      `p${plate.gridY}${plate.gridX} 纵向出框：${(Math.abs(plate.y) + plate.height / 2 - halfH).toFixed(1)}px`)
  }
})

test('地皮互不重叠：重叠就是两栋楼盖在同一块地上', () => {
  for (let i = 0; i < layout.plates.length; i++) {
    for (let j = i + 1; j < layout.plates.length; j++) {
      const a = layout.plates[i] as typeof layout.plates[number]
      const b = layout.plates[j] as typeof layout.plates[number]
      const gapX = Math.abs(a.x - b.x) - (a.width + b.width) / 2
      const gapY = Math.abs(a.y - b.y) - (a.height + b.height) / 2
      assert.ok(gapX >= -0.5 || gapY >= -0.5,
        `p${a.gridY}${a.gridX} 与 p${b.gridY}${b.gridX} 的地皮重叠（${gapX.toFixed(1)}×${gapY.toFixed(1)}）`)
    }
  }
})

test('投影不是均匀棋盘：每一行的水平间距都至少有一个不等', () => {
  for (let row = 0; row < SCENE_GRID_HEIGHT; row++) {
    const xs = Array.from({ length: SCENE_GRID_WIDTH },
      (_, column) => plateAt(column, row).x).sort((m, n) => m - n)
    const gaps = xs.slice(1).map((x, i) => Math.round((x - (xs[i] as number)) * 10) / 10)
    assert.ok(new Set(gaps).size > 1,
      `第 ${row} 行的水平间距全相等（${gaps.join('/')}）—— 这就是棋盘，规格 §3.3 禁止的形态`)
  }
})

test('缩放各向同性，且格子的疏密确实拉开了（不是把棋盘换个坐标写）', () => {
  const anchor = SCENE_ANCHORS[0] as typeof SCENE_ANCHORS[number]
  const plate = plateAt(anchor.gridX, anchor.gridY)
  assert.ok(Math.abs(plate.width / anchor.footprintWidth - layout.scale) < 1e-9,
    '横向缩放与 layout.scale 不符 ⇒ 两轴被分别缩放了')
  assert.ok(Math.abs(plate.height / anchor.footprintDepth - layout.scale) < 1e-9,
    '纵向缩放与 layout.scale 不符 ⇒ 两轴被分别缩放了')
  // 棋盘的样子是"所有脚印一样大且等距"；这里要求投影后的宽度**至少出现三种**
  const widths = new Set(layout.plates.map((p) => Math.round(p.width)))
  assert.ok(widths.size >= 3, `投影后只有 ${widths.size} 种地皮宽度，读起来仍是一排排等宽格子`)
})

test('非均匀锚点按真实落地深度绘制，服务器后排不盖住画面前排', () => {
  const sorted = scenePlatesBackToFront(layout.plates)
  const front = sorted.findIndex((p) => p.gridX === 5 && p.gridY === 2)
  const back = sorted.findIndex((p) => p.gridX === 5 && p.gridY === 3)
  assert.ok(front > back, 'p25 在画面前方，却比 p35 先画；按 gridY 排会重现这处遮挡')
  for (let i = 1; i < sorted.length; i++) {
    assert.ok(sorted[i]!.depth >= sorted[i - 1]!.depth)
  }
  assert.equal(new Set(sorted.map((p) => `${p.gridX},${p.gridY}`)).size, layout.plates.length)
})

test('深度排序不改变源数组或格位，输入次序变化也不让同深度建筑闪烁', () => {
  const original = [...layout.plates]
  const sorted = scenePlatesBackToFront(layout.plates)
  assert.deepEqual(layout.plates, original)
  assert.deepEqual(scenePlatesBackToFront([...layout.plates].reverse()), sorted)
  assert.deepEqual([...sorted].sort((a, b) => a.gridY * 6 + a.gridX - b.gridY * 6 - b.gridX),
    [...original].sort((a, b) => a.gridY * 6 + a.gridX - b.gridY * 6 - b.gridX))
})

/** 实测微信横屏 HUD：操作栏世界 y76..152，Queue 为 H-56、27 高，名字等级盒27高。 */
const nativeFraming = (width: number, height: number, insets = { left: 0, right: 0, top: 0, bottom: 0 }) => {
  const projected = projectSceneLayout(width, height)
  const main = projected.plates.find(plate => plate.gridX === 3 && plate.gridY === 3)!
  const zoom = 1.5
  const footRatio = buildingArtFootRatio('main_city')
  const preferred = Math.max(76, Math.min(300, height * 0.36, main.width * 2.30))
  const safe = { bottom: insets.bottom + 152 + 4 - height / 2,
    top: height / 2 - insets.top - 56 - 27 / 2 - 4 }
  const caption = { bottom: -16 - 27 / 2, top: -16 + 27 / 2 }
  const fit = fitSceneLandmark(height, zoom, main.y, preferred, footRatio, caption, safe)
  return { main, zoom, footRatio, preferred, safe, caption, ...fit }
}

/** 独立测联合边界，不复算 fitSceneLandmark 的尺寸或平移公式作为“期望”。 */
const framingBounds = (frame: ReturnType<typeof nativeFraming>) => ({
  bodyBottom: frame.stageY + (frame.main.y - frame.size * frame.footRatio) * frame.zoom,
  bodyTop: frame.stageY + (frame.main.y + frame.size * (1 - frame.footRatio)) * frame.zoom,
  captionBottom: frame.stageY + (frame.main.y + frame.caption.bottom) * frame.zoom,
  captionTop: frame.stageY + (frame.main.y + frame.caption.top) * frame.zoom,
})

const framingSafe = (frame: ReturnType<typeof nativeFraming>) => {
  const bounds = framingBounds(frame)
  return Math.min(bounds.bodyBottom, bounds.captionBottom) >= frame.safe.bottom - 1e-7
    && Math.max(bounds.bodyTop, bounds.captionTop) <= frame.safe.top + 1e-7
}

for (const [width, height] of [[960, 443.6018957345971], [960, 480], [960, 600], [960, 640], [1440, 900]] as const) {
  test(`${width}×${height}默认主堡Sprite及27px名字等级联合边界均避开实际HUD，舞台不露底`, () => {
    const frame = nativeFraming(width, height)
    assert.ok(framingSafe(frame), JSON.stringify({ frame, bounds: framingBounds(frame) }))
    assert.ok(Math.abs(frame.stageY) <= height * (frame.zoom - 1) / 2 + 1e-7)
    assert.ok(frame.size > 0 && frame.size <= frame.preferred)
    assert.equal(frame.caption.bottom, -29.5, '不能用22px薄框替代27px文字盒')
  })
}

test('微信443.6高负控：旧主体居中与整视口尺寸同一联合边界门实红', () => {
  const frame = nativeFraming(960, 443.6018957345971)
  const limit = 443.6018957345971 * (frame.zoom - 1) / 2
  const oldStage = Math.max(-limit, Math.min(limit,
    -(frame.main.y + frame.preferred * (0.5 - frame.footRatio)) * frame.zoom))
  const old = { ...frame, size: frame.preferred, stageY: oldStage }
  assert.equal(framingSafe(old), false)
  const oldBounds = framingBounds(old)
  assert.ok(oldBounds.captionBottom < frame.safe.bottom, '负控必须真的遮住名字下缘')
  assert.ok(oldBounds.bodyTop > frame.safe.top, '负控必须真的撞进Queue盒')
  assert.equal(framingSafe(frame), true)
})

test('微信443.6高负控：只上移旧尺寸救铭牌仍碰Queue，同一边界门不接受半修复', () => {
  const frame = nativeFraming(960, 443.6018957345971)
  const onlyLifted = { ...frame, size: frame.preferred,
    stageY: frame.safe.bottom - (frame.main.y + frame.caption.bottom) * frame.zoom }
  assert.ok(framingBounds(onlyLifted).captionBottom >= frame.safe.bottom - 1e-7)
  assert.equal(framingSafe(onlyLifted), false)
  assert.ok(framingBounds(onlyLifted).bodyTop > frame.safe.top)
})

test('600/640和大视口已安全的原构图保持主堡尺寸及原居中夹取位置', () => {
  for (const [width, height] of [[960, 600], [960, 640], [1440, 900]] as const) {
    const frame = nativeFraming(width, height)
    const limit = height * (frame.zoom - 1) / 2
    const centered = Math.max(-limit, Math.min(limit,
      -(frame.main.y + frame.preferred * (0.5 - frame.footRatio)) * frame.zoom))
    assert.equal(frame.size, frame.preferred)
    assert.ok(Math.abs(frame.stageY - centered) < 1e-7, `${width}×${height}无故改变已有安全构图`)
  }
})

test('真实p23较高落点需要额外缩主体，联合边界与舞台铺满区间仍有可行交集', () => {
  const base = nativeFraming(960, 443.6018957345971)
  const plate = projectSceneLayout(960, 443.6018957345971).plates
    .find(row => row.gridX === 3 && row.gridY === 2)!
  const fit = fitSceneLandmark(443.6018957345971, base.zoom, plate.y, base.preferred,
    base.footRatio, base.caption, base.safe)
  const frame = { ...base, main: plate, ...fit }
  assert.ok(framingSafe(frame), JSON.stringify({ frame, bounds: framingBounds(frame) }))
  assert.ok(frame.size < base.size, '较高落点不能沿用仅按总高度算出的主体边长')
  assert.ok(Math.abs(frame.stageY) <= 443.6018957345971 * (frame.zoom - 1) / 2 + 1e-7)
})

test('不可达铭牌不会通过联合可见性门，构图仍遵守舞台铺满边界', () => {
  const base = nativeFraming(960, 443.6018957345971)
  const main = { ...base.main, y: -200 }
  const fit = fitSceneLandmark(443.6018957345971, base.zoom, main.y, base.preferred,
    base.footRatio, base.caption, base.safe)
  const frame = { ...base, main, ...fit }
  assert.equal(framingSafe(frame), false, '没有可行交集就不能称为安全构图')
  assert.ok(Number.isFinite(frame.stageY) && Math.abs(frame.stageY) <= 443.6018957345971 * (frame.zoom - 1) / 2)
})

test('微信真实刘海和手势边距抬高操作栏后，默认主堡与完整铭牌仍处于实际HUD净区', () => {
  const height = 443.6018957345971
  const insets = safeAreaInsets(960, height, { x: 53.459715639810426, y: 23.886255924170616,
    width: 853.0805687203791, height: 419.7156398104265 })
  const frame = nativeFraming(960, height, insets)
  assert.ok(framingSafe(frame), JSON.stringify({ frame, bounds: framingBounds(frame) }))
  assert.ok(frame.size < nativeFraming(960, height).size, '不能沿用未扣底部手势区的主堡边长')
  assert.ok(Math.abs(frame.stageY) <= height * (frame.zoom - 1) / 2 + 1e-7)
})

test('顶部非零安全边距进入实际Queue下沿，联合构图不会顶入状态区', () => {
  const frame = nativeFraming(960, 640, { left: 40, right: 10, top: 30, bottom: 18 })
  assert.ok(framingSafe(frame), JSON.stringify({ frame, bounds: framingBounds(frame) }))
  assert.ok(Math.abs(frame.stageY) <= 640 * (frame.zoom - 1) / 2 + 1e-7)
})
