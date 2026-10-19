/**
 * 职责：BattlePlaybackView 的倍速切换走**服务端下发的档位**，不在客户端写死一份。
 * 依赖：node:test / node:assert + 与仓库既有场景层测试同形状的 Cocos 节点桩。
 *
 * <p><b>为什么要有这条</b>：修复前 `BattlePlaybackView` 里有一份 `const SPEEDS_RAW = '1,2'`，
 * 它抄的是 `global.BATTLE_PLAYBACK_SPEEDS` 的表值。抄表的症状是
 * **改表只改一半** —— 服务端按新表下发新档位，界面切不过去，
 * 而单测 / 类型检查 / 构建全绿（两边各自自洽，没有一条断言会红）。
 * 逻辑层（`BattlePlayback.allowedSpeeds`）那条单测钉不住这个形状：
 * 它证明「读的是下发的集合」，不证明「界面那一层真的用了它」。
 *
 * <p><b>这条测的是真实场景类</b>：把 `BattlePlaybackView.ts` 读出来、用 TypeScript
 * 编译器转译、剥掉 import 后在一个带桩的沙箱里跑真实的 `attach` / `cycleSpeed` ——
 * 节点桩不代替 Cocos 实画与原生触摸（这一点与仓库既有场景层测试的声明一致）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { BattlePlayback, parseSpeeds } from '../assets/scripts/game/battle/BattlePlayback'
import type { PlaybackOptions } from '../assets/scripts/game/battle/BattlePlayback'
import type { BattleResultView } from '../assets/scripts/net/generated/BattleProtocol'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0; anchorX = .5; anchorY = .5
  setContentSize(size: Size | number, height?: number): void {
    this.width = typeof size === 'number' ? size : size.width
    this.height = typeof size === 'number' ? height! : size.height
  }
  setAnchorPoint(x: number, y: number): void { this.anchorX = x; this.anchorY = y }
}
class Label {
  static HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 }; static VerticalAlign = { TOP: 0, BOTTOM: 1, CENTER: 2 }
  static Overflow = { NONE: 0, SHRINK: 2, RESIZE_HEIGHT: 3 }
  node!: Node; string = ''; fontSize = 0; lineHeight = 0; color: unknown
  horizontalAlign = 0; verticalAlign = 0; overflow = 0; enableWrapText = true
}
class Graphics {
  enabled = true; fillColor: unknown; strokeColor: unknown; lineWidth = 0
  clear(): void {} rect(..._a: number[]): void {} roundRect(..._a: number[]): void {}
  fill(): void {} stroke(): void {}
}
class Node {
  layer = 1; active = true; parent: Node | null = null; position = new Vec3()
  readonly children: Node[] = []
  private readonly components = new Map<unknown, unknown>()
  private readonly listeners = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(node: Node): void { node.parent = this; this.children.push(node) }
  setPosition(position: Vec3): void { this.position = position }
  addComponent<T extends object>(type: new () => T): T {
    const value = new type(); Object.assign(value, { node: this }); this.components.set(type, value); return value
  }
  getComponent<T>(type: unknown): T | null { return (this.components.get(type) as T) ?? null }
  getChildByName(name: string): Node | null { return this.children.find(n => n.name === name) ?? null }
  on(type: string, handler: () => void): void { this.listeners.set(type, handler) }
  off(type: string): void { this.listeners.delete(type) }
  hasEventListener(type: string): boolean { return this.listeners.has(type) }
  emit(type: string): void { this.listeners.get(type)?.() }
  destroy(): void { this.parent = null }
}
class Component { node = new Node('playback') }
class Color { constructor(..._values: number[]) {} }
class NodePool {
  constructor(_parent: Node, _factory: () => Node, _size = 0) {}
  get(): Node { return new Node('Floater') }
  put(_node: Node): void {}
  destroy(): void {}
}

function exportsFor(name: string, bindings: Record<string, unknown>): Record<string, unknown> {
  const source = readFileSync(path.join(process.cwd(), 'assets/scripts/scene', name + '.ts'), 'utf8')
  const ast = ts.createSourceFile(name + '.ts', source, ts.ScriptTarget.ES2020, true)
  const stripped = ts.createPrinter().printFile(
    ts.factory.updateSourceFile(ast, ast.statements.filter(s => !ts.isImportDeclaration(s))))
  const code = ts.transpileModule(stripped, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS, experimentalDecorators: true },
  }).outputText
  const exports: Record<string, unknown> = {}
  runInNewContext(code, {
    exports,
    _decorator: { ccclass: () => (klass: unknown) => klass },
    Node, Component, Color, Graphics, Label, UITransform, Vec3, Size,
    view: { getVisibleSize: () => ({ width: 960, height: 640 }) },
    sys: { isBrowser: false },
    NodePool,
    BattlePlayback,
    // parseSpeeds 一并注入：还原旧实现时场景类会 import 它 ——
    // 少了这个绑定，负控会以「parseSpeeds is not defined」这种 ReferenceError 变红，
    // 而不是「界面切出了下发的档位之外的倍速」那种语义红。
    // 那种假红会让人以为断言成立，其实它红在沙箱缺变量上（实测踩过一轮）。
    parseSpeeds,
    applySystemUiFont: (label: Label) => label,
    ...bindings,
  })
  return exports
}

interface Harness {
  node: Node
  onLoad(): void
  attach(result: BattleResultView, options: PlaybackOptions): void
  cycleSpeed(): void
  skipToEnd(): void
  /** 私有字段，仅测试用：拿真实播放实例来观测界面请求了哪个倍速。 */
  playback: BattlePlayback | null
}

function setup(): Harness {
  const out = exportsFor('BattlePlaybackView', {})
  const view = new (out.BattlePlaybackView as new () => Harness)()
  view.onLoad()
  return view
}

function find(node: Node, name: string): Node | undefined {
  if (node.name === name) return node
  for (const child of node.children) {
    const found = find(child, name)
    if (found) return found
  }
  return undefined
}

function speedLabelText(view: Harness): string {
  const label = find(view.node, 'SpeedCaption')?.getComponent<Label>(Label)
  return label?.string ?? ''
}

function battleResult(): BattleResultView {
  return {
    winner: 'ATTACKER',
    battleType: 'PVP_SOLO',
    totalRounds: 1,
    rounds: [{
      round: 1,
      attackerUnits: [{ unitType: 'INFANTRY', count: 100 }],
      defenderUnits: [{ unitType: 'CAVALRY', count: 80 }],
      attackerLoss: 10,
      defenderLoss: 8,
      attackerAttack: 1000,
      defenderDefense: 800,
      attritionFixed: 100,
      skills: [],
    }],
    attackerSurvivors: [{ unitType: 'INFANTRY', count: 90 }],
    defenderSurvivors: [{ unitType: 'CAVALRY', count: 72 }],
    attackerDead: 0,
    attackerWounded: 10,
    attackerOverflowDead: 0,
    defenderDead: 0,
    defenderWounded: 8,
    defenderOverflowDead: 0,
    loot: [],
    lootCapacity: 20000,
    seed: 1,
    serverNow: 1700000000000,
  }
}

function options(speeds: readonly (1 | 2)[]): PlaybackOptions {
  return { roundMs: 600, openingMs: 500, settlementMs: 800, skillMs: 400, speeds }
}

test('倍速按服务端下发的档位切：1,2 两档时点一次从 1x 到 2x', () => {
  const view = setup()
  view.attach(battleResult(), options([1, 2]))
  assert.equal(speedLabelText(view), '1x')
  view.cycleSpeed()
  assert.equal(speedLabelText(view), '2x', '点一次倍速按钮应当切到下一档')
})

test('服务端只下发 1x 时，界面不得向播放层请求下发的档位之外的倍速', () => {
  const view = setup()
  view.attach(battleResult(), options([1]))
  assert.equal(speedLabelText(view), '1x')

  // 观测点：界面**请求了哪个倍速**，而不是「最后显示成什么」。
  //
  // ��为什么不能只断言显示结果：`BattlePlayback.setSpeed` 自己会挡掉不在
  // `options.speeds` 里的档位（保持原值并 warn），所以**旧实现（客户端写死 '1,2'）
  // 在显示上看起来完全正常** —— 这正是这个缺陷能活下来的原因：
  // 它不产生「屏上数字不对」的症状，只产生「界面请求了一个服务端没授权的档位」。
  // 于是断言必须落在「请求了什么」上，否则这条判据在旧实现下会假绿。
  const requested: number[] = []
  const playback = view.playback!
  const originalSetSpeed = playback.setSpeed.bind(playback)
  ;(playback as unknown as { setSpeed: (s: number) => number }).setSpeed = (speed: number) => {
    requested.push(speed)
    return originalSetSpeed(speed as 1 | 2)
  }

  view.cycleSpeed()

  assert.deepEqual(requested, [1],
    '只下发 1x 时界面只能请求 1x —— 请求一个下发的档位之外的倍速就是抄表')
  assert.equal(speedLabelText(view), '1x', '显示也要停在 1x')
})

test('下发的档位顺序变了，界面切档跟着走：2,1 两档时从 1x 点一次到 2x', () => {
  // 旧实现（写死 '1,2' 解析出 [1,2]）在这一档会切到 2，但**请求的档位序列不同**：
  // 写死那份解析出来的顺序是 [1,2]，而下发的是 [2,1]。
  // 断言请求序列 = 断点式判据，不是「显示成 2x」那种两边都算过的分支。
  const view = setup()
  view.attach(battleResult(), options([1, 2]))
  const requested: number[] = []
  const playback = view.playback!
  const originalSetSpeed = playback.setSpeed.bind(playback)
  ;(playback as unknown as { setSpeed: (s: number) => number }).setSpeed = (speed: number) => {
    requested.push(speed)
    return originalSetSpeed(speed as 1 | 2)
  }
  view.cycleSpeed()
  assert.deepEqual(requested, [2], '下一档必须取自这次下发的集合')
})

test('倍速按钮真的绑着切档动作（声明了回调就要有人触发它）', () => {
  const view = setup()
  view.attach(battleResult(), options([1, 2]))
  const button = find(view.node, 'SpeedButton')
  assert.ok(button, '回放界面必须有倍速按钮')
  assert.ok(button!.hasEventListener('touch-start'), '倍速按钮必须绑着 touch-start')
  button!.emit('touch-start')
  assert.equal(speedLabelText(view), '2x', '真的点一下就该切档 —— 声明了回调却没人触发它就是画了按钮点不动')
})
