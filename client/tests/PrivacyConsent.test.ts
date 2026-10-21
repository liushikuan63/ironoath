/**
 * 职责：隐私授权判定的证据（上线检查清单 §二 5）。
 * 依赖：真实的 PrivacyConsent 模块（纯函数）。
 *
 * <p>为什么这些规则值得单测：它们决定「启动时会不会给玩家弹一个平台的协议弹窗」，
 * 而弹错方向的两种代价都不小 —— 该弹不弹是合规问题（平台要求先取得同意再用隐私接口），
 * 不该弹乱弹是体验问题（已经同意过的人每次进游戏都被拦一下）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { authorizePrivacy, planPrivacyPrompt } from '../assets/scripts/game/privacy/PrivacyConsent'

test('查不到平台答案时不猜弹窗状态，并标出接口不可用', () => {
  const plan = planPrivacyPrompt(null)

  assert.equal(plan.request, false, '查询失败不能猜平台会不会弹；启动放行由 authorizePrivacy 决定')
  assert.equal(plan.apiAvailable, false)
  assert.equal(plan.contractName, null)
})

test('平台说还需要授权 ⇒ 发起授权（弹窗由平台画，协议文本由平台给）', () => {
  const plan = planPrivacyPrompt({ needAuthorization: true, privacyContractName: '《用户隐私保护指引》' })

  assert.equal(plan.request, true, '平台要求先取得同意才能用隐私相关接口，而登录就会带上设备与账号标识')
  assert.equal(plan.contractName, '《用户隐私保护指引》', '协议名用平台给的原文，本作不自己起名')
  assert.equal(plan.apiAvailable, true)
})

test('平台说不需要授权 ⇒ 不再打扰（已同意过的人每次进游戏被拦一下，是体验问题）', () => {
  const plan = planPrivacyPrompt({ needAuthorization: false, privacyContractName: '《用户隐私保护指引》' })

  assert.equal(plan.request, false)
  assert.equal(plan.apiAvailable, true, '接口在、只是不需要弹 —— 与"没接口"必须能分开')
})

test('平台没给协议名时 contractName 为 null，界面走兜底文案而不是空字符串', () => {
  const plan = planPrivacyPrompt({ needAuthorization: false })

  assert.equal(plan.contractName, null)
})

test('微信要求授权时必须等待成功回调，拒绝后保持阻断', async () => {
  let approve: (() => void) | undefined
  let reject: (() => void) | undefined
  let resolved = false
  const pending = authorizePrivacy({
    getPrivacySetting: options => options.success({ needAuthorization: true, privacyContractName: '隐私保护指引' }),
    requirePrivacyAuthorize: options => { approve = options.success; reject = options.fail },
  }).then(result => { resolved = true; return result })

  await Promise.resolve()
  assert.equal(resolved, false, '平台弹窗尚未回调时，启动流程不得继续登录')
  assert.equal(typeof approve, 'function')
  reject?.()
  const denied = await pending
  assert.equal(denied.allowed, false)
  assert.equal(denied.blockedReason, 'denied')
  assert.equal(denied.plan.request, true)

  const accepted = authorizePrivacy({
    getPrivacySetting: options => options.success({ needAuthorization: true }),
    requirePrivacyAuthorize: options => options.success(),
  })
  assert.equal((await accepted).allowed, true, '只有授权成功才允许继续启动')
})

test('已授权不再弹；微信查询失败或缺授权接口时不得假定已同意；浏览器继续可预览', async () => {
  let prompted = 0
  const already = await authorizePrivacy({
    getPrivacySetting: options => options.success({ needAuthorization: false }),
    requirePrivacyAuthorize: () => { prompted += 1 },
  })
  assert.equal(already.allowed, true)
  assert.equal(prompted, 0)
  assert.equal((await authorizePrivacy({
    getPrivacySetting: options => options.fail(new Error('offline')),
  })).blockedReason, 'unavailable')
  assert.equal((await authorizePrivacy({
    getPrivacySetting: options => options.success({ needAuthorization: true }),
  })).blockedReason, 'unavailable')
  assert.equal((await authorizePrivacy({})).blockedReason, 'unavailable')
  assert.equal((await authorizePrivacy(null)).allowed, true)
})
