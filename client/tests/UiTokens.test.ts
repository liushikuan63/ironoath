/**
 * 职责：钉住 V25-a 的视觉常量口径 —— 每个值都要能指回一个现跑出处，未定案的必须还是 null。
 * 依赖：node:test / node:assert / node:fs。
 *
 * <p>为什么每条都值得红：
 * ① 遮罩/底色这类值一旦允许"各面板再定一份"，本文件就变成第三真相（#794 记的正是 17 处 5 值）；
 * ② 未定案的颜色如果被填上一个"看着合理"的值，就没有任何东西记得它还没被目视过（规格 §七 Q2）；
 * ③ 九宫格框带厚如果有第二份，后写的生效、先写的变成死字段（#213 真咬到过）；
 * ④ `game/` 层一旦 import 引擎，这层的用例会在 import 阶段崩掉 —— 而这些常量最需要被用例钉住。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import * as tokens from '../assets/scripts/game/ui/UiTokens'

const DECIDED: Array<[string, tokens.Rgba]> = [
  ['MASK_SCRIM', [8, 6, 5, 190]],
  ['PANEL_FALLBACK', [40, 33, 27, 255]],
  ['IRON_SURFACE', [22, 18, 16, 255]],
  ['BRONZE_GOLD', [184, 134, 11, 255]],
]

test('已定案的四个颜色精确等于现跑出处值，不接受就近取整', () => {
  for (const [name, expected] of DECIDED) {
    const actual = (tokens as Record<string, unknown>)[name] as tokens.Rgba
    assert.deepEqual([...actual], [...expected], `${name} 变了 —— 它是 17 处局部常量收敛后的唯一真源，改值要连出处一起改`)
  }
})

test('每个 RGBA 元组都是四通道、且通道落在 0~255', () => {
  for (const [name, value] of DECIDED) {
    assert.equal(value.length, 4, `${name} 必须是 RGBA 四通道`)
    for (const channel of value) {
      assert.ok(Number.isInteger(channel) && channel >= 0 && channel <= 255, `${name} 有通道越界：${channel}`)
    }
  }
})

test('未定案的材质色必须还是 null，不许填感觉值', () => {
  assert.equal(tokens.OXBLOOD_ACCENT, null, '暗红号色要等 V25-b 出图目视定案（规格 §七 Q2）')
  assert.equal(tokens.PARCHMENT_FACE, null, '羊皮纸亮度涉及"浅底配金字会掉对比度"，未目视不许出厂')
})

test('九宫格框带厚不在本文件里 —— 它只有 .png.meta 与 ArtFamilies 那两份且由测试对账', () => {
  const offenders = Object.keys(tokens).filter(k => /FRAME|BAND|INSET|BORDER|SLICE/i.test(k))
  assert.deepEqual(offenders, [], `切分几何的第二真源：${offenders.join(', ')}（#213 的成因就是"两份数字"）`)
})

/** 测试默认跑在 client/ 下（test-client.sh 会 cd），但为别的 cwd 也能跑，逐级向上找仓库根。 */
function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'hero.json'))) {
      return dir
    }
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/hero.json，无法定位源码做守卫')
}

test('game/ 层保持零引擎依赖，否则这层的用例在 import 阶段就崩', () => {
  const src = fs.readFileSync(
    path.join(repoRoot(), 'client', 'assets', 'scripts', 'game', 'ui', 'UiTokens.ts'), 'utf8')
  const engineImport = src
    .split(/\r?\n/)
    .filter(line => /^\s*import\b/.test(line) && /from\s+['"]cc['"]/.test(line))
  assert.deepEqual(engineImport, [], '本文件一旦 import 引擎，node --test 就跑不动了')
})

test('圆角与标题字号取的是现跑主流值', () => {
  // 现跑 roundRect 的 r 参数分布：6(39) / 5(33) / 4(21) / 8(11) / 10(8)
  assert.equal(tokens.CORNER_RADIUS, 6, '圆角要跟着 39 处的主流值，不是新起一个')
  // 现跑标题：22（BagPanelView:291、NationPanelView:262）为主，26 是礼包离群
  assert.equal(tokens.TITLE_FONT_SIZE, 22)
})
