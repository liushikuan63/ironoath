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
import type { PrivacyPlan } from '../assets/scripts/game/privacy/PrivacyConsent'
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

/** 运行环境没有隐私接口（浏览器/编辑器）时的计划。 */
const noPrivacyApi: PrivacyPlan = { request: false, contractName: null, apiAvailable: false }

test('隐私协议那一行：有平台接口就打开平台协议页，没有就给说明（不死按钮）', () => {
  const withApi = buildSettingsView(resp(), '1.0.0',
    { request: true, contractName: '《用户隐私保护指引》', apiAvailable: true })
  const privacyRow = withApi.rows.find(r => r.key === 'privacy')
  assert.ok(privacyRow !== undefined, '隐私入口要一级可见（§二 5）')
  assert.equal(privacyRow.action.kind, 'open-privacy-contract', '有接口就打开平台配置的协议页')
  assert.ok(privacyRow.subtitle.includes('《用户隐私保护指引》'),
    '协议名用平台给的原文，提审时才对得上平台配置：' + privacyRow.subtitle)

  const withoutApi = buildSettingsView(resp(), '1.0.0', noPrivacyApi)
  const fallback = withoutApi.rows.find(r => r.key === 'privacy')
  assert.equal(fallback?.action.kind, 'message', '没有接口时也不能是死按钮')
  assert.ok(fallback?.action.kind === 'message' && fallback.action.text.includes('微信小游戏'),
    '说明要讲清「这个环境看不了」，而不是「坏了」')
})
test('未配置客服时入口照样在，点下去说明未配置（不是死按钮）', () => {
  const view = buildSettingsView(resp({ support: null }), '1.0.0', noPrivacyApi)

  const keys = view.rows.map(r => r.key)
  assert.deepEqual(keys, ['audio', 'support', 'refund', 'privacy'],
    '音效 + 客服、退款、隐私四个入口都要一级可见（提审按 §二 5/8/9 查）')

  for (const row of view.rows.filter(r => r.key === 'support' || r.key === 'refund')) {
    assert.equal(row.action.kind, 'message',
      `${row.key} 在未配置时也该有反应：点了什么都不发生会被当成 bug`)
    assert.ok(row.action.kind === 'message' && row.action.text.includes('未配置'),
      `${row.key} 的说明要说清是「没配置」而不是「坏了」：` + JSON.stringify(row.action))
  }
  assert.equal(view.rows.find(r => r.key === 'support')?.subtitle, '本环境未配置客服')
})

test('配置了客服 ⇒ 两个入口都走 open-customer-service，并原样带上 corpId 与 url', () => {
  const view = buildSettingsView(resp({
    support: { corpId: 'corp-x', url: 'https://work.weixin.qq.com/kf/x' },
  }), '1.0.0')

  for (const row of view.rows.filter(r => r.key === 'support' || r.key === 'refund')) {
    assert.equal(row.action.kind, 'open-customer-service', row.key + ' 应当直接打开客服')
    assert.ok(row.action.kind === 'open-customer-service')
    assert.equal(row.action.corpId, 'corp-x')
    assert.equal(row.action.url, 'https://work.weixin.qq.com/kf/x')
  }
  // 退款与客服同路（B15 §3 的「退款通道」就是客服），所以两行的目标是同一个
  const supportRow = view.rows.find(r => r.key === 'support')
  const refundRow = view.rows.find(r => r.key === 'refund')
  assert.deepEqual(supportRow?.action, refundRow?.action)
})

test('服务端版本没拿到时页面照常能用：入口还在，版本行只报当前版本', () => {
  const view = buildSettingsView(null, '1.0.0')

  assert.equal(view.rows.length, 4, '拿不到版本响应不该让设置页空掉（四个入口照常）')
  assert.equal(view.versionText, '当前版本 1.0.0', '没有服务端版本就不显示「最新」，不编一个')
})

test('音效行：默认开、排第一、点下去是 toggle-audio（不是一句"请去系统设置"）', () => {
  const on = buildSettingsView(null, '1.0.0')
  const off = buildSettingsView(null, '1.0.0', noPrivacyApi, true)

  // 排第一是有意的：这一页只有它是"点了立刻能听见"的，藏在客服下面玩家会以为游戏没有音效开关
  assert.equal(on.rows[0]?.key, 'audio')
  assert.equal(on.rows[0]?.action.kind, 'toggle-audio')
  assert.ok(on.rows[0]?.subtitle.includes('开'), '默认状态要写在副标题里，不能只靠一个看不懂的勾选框')
  assert.ok(off.rows[0]?.subtitle.includes('静音'), '静音后副标题必须换成"当前静音"，否则下次进来不知道刚才点没点上')
  assert.notDeepEqual(on.rows[0]?.subtitle, off.rows[0]?.subtitle,
    '两态文案一模一样 ⇒ 这一行等于没反馈')
})

test('版本行：与服务端一致时只显示当前版本，不一致时把两个都写出来', () => {
  assert.equal(buildSettingsView(resp({ latest: '1.0.0' }), '1.0.0').versionText,
    '当前版本 1.0.0')
  assert.equal(buildSettingsView(resp({ latest: '2.0.0' }), '1.0.0').versionText,
    '当前版本 1.0.0 ｜ 最新 2.0.0',
    '玩家报障时第一句话就是问版本，两个数一起给才不会来回问')
})
