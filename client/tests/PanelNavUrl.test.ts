/**
 * 职责：初始面板 URL 入口在 Web 可用、小游戏缺少浏览器 API 时安静回退。
 * 场景依赖 cc，这里只执行生产方法及 onLoad 的初始面板表达式，不导入渲染层。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import { URLSearchParams } from 'node:url'

const clientDir = existsSync(path.resolve(process.cwd(), 'client/assets'))
  ? path.resolve(process.cwd(), 'client') : process.cwd()
const source = readFileSync(path.join(clientDir, 'assets/scripts/scene/PanelNav.ts'), 'utf8')
const body = /^  private initialPanelFromUrl\(\): string \| null \{([\s\S]*?)^  \}/m.exec(source)?.[1]
const initial = /this\.show\((this\.initialPanelFromUrl\(\)[^\r\n]+)\)/.exec(source)?.[1]
const panels = /^const PANELS:[\s\S]*?= \[([\s\S]*?)^\]/m.exec(source)?.[1]
assert.ok(body !== undefined && initial !== undefined && panels !== undefined,
  '必须执行生产的 URL 解析、默认面板表达式与面板清单，不能在测试里复制一套实现')
const panelKeys = Array.from(panels.matchAll(/\{ key: '([^']+)'/g), match => match[1]!)

function initialPanel(environment: Record<string, unknown>): { panel: string, warnings: unknown[][] } {
  const warnings: unknown[][] = []
  const panel = runInNewContext(`
    (function () {
      this.initialPanelFromUrl = function () { ${body} };
      return ${initial};
    }).call({ panelNodes: new Map(PANELS.map(def => [def.key, true])) });
  `, {
    ...environment,
    PANELS: panelKeys.map(key => ({ key })),
    console: { warn: (...args: unknown[]) => warnings.push(args) },
  }) as string
  return { panel, warnings }
}

test('没有 location 或 URLSearchParams 的小游戏安静进入默认内城', () => {
  assert.deepEqual(initialPanel({}), { panel: 'city', warnings: [] })
})

test('微信 IDE 有宿主 location 但没有 URLSearchParams 时不读取查询、不警告', () => {
  let reads = 0
  const location = {
    get search(): string {
      reads += 1
      return '?projectid=ide&panel=world'
    },
  }
  assert.deepEqual(initialPanel({ location }), { panel: 'city', warnings: [] })
  assert.equal(reads, 0, '浏览器解析器不可用时，宿主查询也不是小游戏深链入口')
})

test('Web 空查询保留默认内城', () => {
  assert.deepEqual(initialPanel({ location: { search: '' }, URLSearchParams }),
    { panel: 'city', warnings: [] })
})

test('Web 已知面板与编码后的面板名仍正常打开', () => {
  for (const [search, panel] of [
    ['?panel=world', 'world'],
    ['?panel=social', 'social'],
    ['?other=1&panel=%77orld', 'world'],
  ]) {
    assert.deepEqual(initialPanel({ location: { search }, URLSearchParams }),
      { panel, warnings: [] })
  }
})

test('Web 未知、缺失与空面板值均安静回退默认内城', () => {
  for (const search of ['?panel=unknown_panel', '?other=world', '?panel=']) {
    assert.deepEqual(initialPanel({ location: { search }, URLSearchParams }),
      { panel: 'city', warnings: [] })
  }
})

test('Web 重复 panel 参数保留原有首值行为', () => {
  assert.deepEqual(initialPanel({ location: { search: '?panel=world&panel=city' }, URLSearchParams }),
    { panel: 'world', warnings: [] })
})

test('Web 解析器的真实异常仍回退并保留告警', () => {
  class BrokenSearchParams {
    constructor() { throw new Error('test query parser failure') }
  }
  const result = initialPanel({ location: { search: '?panel=world' }, URLSearchParams: BrokenSearchParams })
  assert.equal(result.panel, 'city')
  assert.equal(result.warnings.length, 1)
  assert.equal(result.warnings[0]?.[0], '[PanelNav] 解析 ?panel 失败，使用默认面板')
})
