/**
 * 职责：设置页的展示数据与点击语义（上线检查清单 §二 8/9）。
 * 依赖：真实的 SettingsPanel 模块。
 *
 * <p>这里钉的不是"画了几个字"，而是两条会被提审直接问到的性质：
 * **入口一级可见**（未配置时也不能藏起来）与 **点下去一定有反应**（未配置时是一条说明，
 * 不是死按钮 —— 死按钮会被当成 bug 报上来，而"没配"是部署事实，不是故障）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSettingsView } from '../assets/scripts/game/settings/SettingsPanel'
import type { AppVersionResp } from '../assets/scripts/net/generated/OpsProtocol'

function resp(overrides: Partial<AppVersionResp> = {}): AppVersionResp {
  return {
    latest: '1.0.0',
    forceUpdate: false,
    grayEnabled: false,
    notice: null,
    trackPolicy: { maxBatchSize: 10, flushSeconds: 10 },
    support: null,
    ...overrides,
  }
}

test('未配置客服时入口照样在，点下去说明未配置（不是死按钮）', () => {
  const view = buildSettingsView(resp({ support: null }), '1.0.0')

  const keys = view.rows.map(r => r.key)
  assert.deepEqual(keys, ['support', 'refund'], '客服与退款两个入口都要一级可见（提审按这条查）')

  for (const row of view.rows) {
    assert.equal(row.action.kind, 'message',
      `${row.key} 在未配置时也该有反应：点了什么都不发生会被当成 bug`)
    assert.ok(row.action.kind === 'message' && row.action.text.includes('未配置'),
      `${row.key} 的说明要说清是「没配置」而不是「坏了」：` + JSON.stringify(row.action))
  }
  assert.equal(view.rows[0]?.subtitle, '本环境未配置客服')
})

test('配置了客服 ⇒ 两个入口都走 open-customer-service，并原样带上 corpId 与 url', () => {
  const view = buildSettingsView(resp({
    support: { corpId: 'corp-x', url: 'https://work.weixin.qq.com/kf/x' },
  }), '1.0.0')

  for (const row of view.rows) {
    assert.equal(row.action.kind, 'open-customer-service', row.key + ' 应当直接打开客服')
    assert.ok(row.action.kind === 'open-customer-service')
    assert.equal(row.action.corpId, 'corp-x')
    assert.equal(row.action.url, 'https://work.weixin.qq.com/kf/x')
  }
  // 退款与客服同路（B15 §3 的「退款通道」就是客服），所以两行的目标是同一个
  assert.deepEqual(view.rows[0]?.action, view.rows[1]?.action)
})

test('服务端版本没拿到时页面照常能用：入口还在，版本行只报当前版本', () => {
  const view = buildSettingsView(null, '1.0.0')

  assert.equal(view.rows.length, 2, '拿不到版本响应不该让设置页空掉')
  assert.equal(view.versionText, '当前版本 1.0.0', '没有服务端版本就不显示「最新」，不编一个')
})

test('版本行：与服务端一致时只显示当前版本，不一致时把两个都写出来', () => {
  assert.equal(buildSettingsView(resp({ latest: '1.0.0' }), '1.0.0').versionText,
    '当前版本 1.0.0')
  assert.equal(buildSettingsView(resp({ latest: '2.0.0' }), '1.0.0').versionText,
    '当前版本 1.0.0 ｜ 最新 2.0.0',
    '玩家报障时第一句话就是问版本，两个数一起给才不会来回问')
})
