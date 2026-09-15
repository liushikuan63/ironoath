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
import { planPrivacyPrompt } from '../assets/scripts/game/privacy/PrivacyConsent'

test('查不到（没有接口或查询失败）⇒ 不弹，并如实标出"这个环境没有隐私接口"', () => {
  const plan = planPrivacyPrompt(null)

  assert.equal(plan.request, false, '把启动卡在一次失败的查询上，比"这次没弹"糟得多')
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
