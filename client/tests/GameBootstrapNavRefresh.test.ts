/** 执行生产导航回调，任务页必须读取升级后的权威进度。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'

const clientDir = existsSync(path.resolve(process.cwd(), 'client/assets'))
  ? path.resolve(process.cwd(), 'client') : process.cwd()
const source = readFileSync(path.join(clientDir, 'assets/scripts/scene/GameBootstrap.ts'), 'utf8')
const body = /this\.nav\.onShow = key => \{([\s\S]*?)\r?\n    \}\r?\n    void this\.boot\(\)/.exec(source)?.[1]
assert.ok(body !== undefined, '必须执行生产导航回调，不复制一套刷新逻辑')

function navigation(previous = 'city'): { show: (key: string) => void, calls: string[][] } {
  const calls: string[][] = []
  const refresh = (...keys: string[]) => { calls.push(['refresh', ...keys]); return Promise.resolve() }
  const root = {
    refresh,
    leaveWorld: () => calls.push(['leaveWorld']),
    openGacha: () => calls.push(['openGacha']),
    loadSocialGates: () => calls.push(['loadSocialGates']),
    loadSocialDiscovery: () => calls.push(['loadSocialDiscovery']),
    reloadRankTab: () => calls.push(['reloadRankTab']),
  }
  const show = runInNewContext(`(function () {
    let previousKey = previous;
    return key => { ${body} };
  }).call(host)`, { previous, host: { root, guide: { repaint: () => undefined } } }) as (key: string) => void
  return { show, calls }
}

test('升级收割后打开任务页，同时重拉任务进度与活动窗口', () => {
  const nav = navigation()
  nav.show('quest')
  assert.deepEqual(nav.calls, [['refresh', 'quest', 'activity']])
})

test('每次返回任务页都重新拉取，后续升级不能复用首次进入的进度', () => {
  const nav = navigation()
  nav.show('quest')
  nav.show('city')
  nav.show('quest')
  assert.deepEqual(nav.calls, [
    ['refresh', 'quest', 'activity'], ['refresh', 'quest', 'activity'],
  ])
})

test('内城不会因为任务刷新而增加首屏请求', () => {
  const nav = navigation()
  nav.show('city')
  assert.deepEqual(nav.calls, [])
})

test('离开地图进入任务时仍执行既有地图解绑', () => {
  const nav = navigation('world')
  nav.show('quest')
  assert.deepEqual(nav.calls, [['refresh', 'quest', 'activity'], ['leaveWorld']])
})

test('社交页既有权限和发现列表刷新保持', () => {
  const nav = navigation()
  nav.show('social')
  assert.deepEqual(nav.calls, [['loadSocialGates'], ['loadSocialDiscovery']])
})
