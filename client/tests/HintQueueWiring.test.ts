/**
 * 职责：钉住 B04 验收 8 的接线形状 —— 提示必须走串行队列，而队列的三个参数必须来自服务端下发。
 *
 * <p>为什么是源码级判据而不是跑一遍界面：`GameBootstrap` 是 cc 组件，headless 单测里没有 cc 运行时
 * （与 `GiftPopupCopy.test.ts` 同一条理由）。而这一族缺陷的形状恰好是"代码在、没接上"：
 * `RewardToastQueue` 与它的 6 条单测早就全绿，玩家侧却一条都没用上（台账 #770 同族）。
 *
 * <p>每条判据都能失败，且都对应一个具体的错法：
 *   ① 删掉 `new RewardToastQueue(` ⇒ 红；② 把参数改成写死的数字 ⇒ 红；
 *   ③ 让 showHint 直接画（回到旧实现）⇒ 红；④ 把 resolve 提到 destroy 之前 ⇒ 红。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (existsSync(path.join(dir, 'contract', 'proto', 'player.schema.json'))) {
      return dir
    }
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/proto/player.schema.json，无法核对提示队列的接线')
}

const ROOT = repoRoot()
const GB = readFileSync(path.join(ROOT, 'client/assets/scripts/scene/GameBootstrap.ts'), 'utf8')
const AR = readFileSync(path.join(ROOT, 'client/assets/scripts/game/session/AppRoot.ts'), 'utf8')
/** 剥掉注释再扫：注释里举反例（"不能写死 120"）不该把门判红。 */
const CODE = GB.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

test('提示出口确实接上了串行队列（画了不等于排了队）', () => {
  assert.ok(/new RewardToastQueue\(/.test(CODE),
    'GameBootstrap 里没有 new RewardToastQueue —— showHint 仍是"每次都往同一坐标 add"')
  assert.ok(/queue\.enqueue\(\[/.test(CODE),
    'showHint 没有把提示交给队列（多条必然叠在一起，正是 B04 验收 8 禁止的）')
  assert.ok(/private paintHint\(/.test(CODE),
    '绘制没有被拆成单独一支 —— 队列就没有"播完"这个时刻可以等')
})

test('队列的三个参数来自 init 下发，客户端一个都不写死', () => {
  assert.ok(/gapMs:\s*toast\.gapMs/.test(CODE), 'gapMs 不是从下发的 toast 列取的')
  assert.ok(/maxQueued:\s*toast\.maxQueued/.test(CODE), 'maxQueued 不是从下发的 toast 列取的')
  assert.ok(/stuckTimeoutMs:\s*toast\.stuckTimeoutMs/.test(CODE), 'stuckTimeoutMs 不是从下发的 toast 列取的')
  // 正向判据之外再钉一条反向的：写死数字（含"看起来合理"的 120/5/8000）一律红
  const hardcoded = CODE.match(/(gapMs|maxQueued|stuckTimeoutMs):\s*\d+/g) ?? []
  assert.deepEqual(hardcoded, [],
    `客户端写死了队列参数：${hardcoded.join('、')} —— 数值住在 global 表里，客户端只有类型（B00 铁律）`)
  assert.ok(/this\.targets\.hintTuning\?\.\(outcome\.data\.toast/.test(AR),
    'AppRoot 没有把 init 响应里的 toast 递给表现层 —— 下发了也没人接')
})

test('播完才 resolve：先销毁再 resolve，顺序反了队列就会叠着放', () => {
  const destroyAt = CODE.indexOf('hint.destroy()')
  const resolveAt = CODE.indexOf('resolve()')
  assert.ok(destroyAt >= 0 && resolveAt >= 0, '找不到销毁或 resolve 那两句，判据走不到')
  assert.ok(destroyAt < resolveAt,
    'resolve() 排在 hint.destroy() 之前 —— 队列会在提示还挂在屏上时就放下一条进来')
})
