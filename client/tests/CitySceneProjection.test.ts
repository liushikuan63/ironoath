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
} from '../assets/scripts/game/city/CitySceneAnchors'

/** 与 `CityPanelView` 同一组数：内容区 536×296、内缩 6。 */
const AREA_WIDTH = 6 * 86 + 5 * 4
const AREA_HEIGHT = 6 * 46 + 5 * 4
const PADDING = 6
const layout = projectSceneLayout(AREA_WIDTH, AREA_HEIGHT, PADDING)

const plateAt = (gridX: number, gridY: number) => {
  const plate = layout.plates.find((p) => p.gridX === gridX && p.gridY === gridY)
  assert.ok(plate !== undefined, `第 ${gridY} 行第 ${gridX} 列没有投影落点`)
  return plate
}

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
