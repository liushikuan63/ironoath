/**
 * 职责：设置页的展示数据与「点下去会发生什么」（上线检查清单 §二 8/9：客服与退款入口设置页一级可见）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：客服配没配由服务端下发（`support` 为 null 即未配置），
 * 这里的"能不能点"只是把服务端的事实转成界面语言，不是自己判断。
 *
 * <p><b>为什么未配置时仍然显示入口</b>：清单原文是「设置页一级可见」，而提审会按这一条查 ——
 * 把入口藏起来，查的人看到的就是"没有这个入口"。所以未配置时入口照常在，
 * 点下去说明「本环境未配置客服」，而不是一个点了没反应的按钮（那会被当成 bug 报上来）。
 */

import type { AppVersionResp, SupportEntry } from '../../net/generated/OpsProtocol'
import type { PrivacyPlan } from '../privacy/PrivacyConsent'

/** 设置页的一行。 */
export interface SettingsRow {
  readonly key: 'support' | 'refund' | 'privacy'
  readonly title: string
  readonly subtitle: string
  /** 点下去会发生什么；恒非空 —— 未配置时是一条说明，不是一个死按钮 */
  readonly action: SettingsAction
}

/** 点击行为。两种都"有反应"，区别只是反应是什么。 */
export type SettingsAction =
  | { readonly kind: 'open-customer-service'; readonly corpId: string; readonly url: string }
  | { readonly kind: 'open-privacy-contract' }
  | { readonly kind: 'message'; readonly text: string }

/** 整个设置页的数据。 */
export interface SettingsView {
  readonly rows: readonly SettingsRow[]
  /** 版本行：「当前 1.0.0 ｜ 最新 2.0.0」；拿不到服务端版本时只显示当前版本 */
  readonly versionText: string
}

/**
 * 组装设置页。
 *
 * @param resp          `/ops/app/version` 的响应；null 表示这次没拿到（页面照常能用）
 * @param clientVersion 当前客户端版本
 */
export function buildSettingsView(
  resp: AppVersionResp | null,
  clientVersion: string,
  privacy: PrivacyPlan = { request: false, contractName: null, apiAvailable: false },
): SettingsView {
  const support = resp?.support ?? null
  const latest = resp?.latest ?? null
  return {
    rows: [
      {
        key: 'support',
        title: '联系客服',
        subtitle: support === null ? '本环境未配置客服' : '遇到问题、误充值、账号异常都可以在这里问',
        action: supportAction(support),
      },
      {
        key: 'refund',
        title: '申请退款',
        subtitle: '未成年人充值退款请通过客服提交（需提供监护人信息）',
        action: supportAction(support),
      },
      {
        key: 'privacy',
        title: '隐私政策',
        // 协议名用平台给的那个（公众平台上配置的《用户隐私保护指引》），本作不自己起名 ——
        // 自己起一个名字，提审时对不上平台配置，是要被打回的那一类
        subtitle: privacy.contractName === null
          ? '查看平台配置的用户隐私保护指引'
          : `查看${privacy.contractName}`,
        action: privacyAction(privacy),
      },
    ],
    versionText: latest === null || latest === clientVersion
      ? `当前版本 ${clientVersion}`
      : `当前版本 ${clientVersion} ｜ 最新 ${latest}`,
  }
}

/**
 * 客服入口点下去做什么。
 *
 * <p>退款与客服同路：B15 §3 要求「退款通道必须留」，而通道就是客服 ——
 * 给它单开一套表单等于自建一个没人看的工单系统，那是另一件事。
 */
/**
 * 隐私协议那一行点下去做什么。
 *
 * <p>运行环境没有隐私接口（浏览器、编辑器预览）时给一条说明而不是死按钮 ——
 * 与客服入口同一条纪律。
 */
function privacyAction(privacy: PrivacyPlan): SettingsAction {
  if (!privacy.apiAvailable) {
    return { kind: 'message', text: '请在微信小游戏内查看隐私政策' }
  }
  return { kind: 'open-privacy-contract' }
}

function supportAction(support: SupportEntry | null): SettingsAction {
  if (support === null) {
    return { kind: 'message', text: '本环境未配置客服，请通过官方渠道联系我们（配置 WECHAT_SUPPORT_* 后可用）' }
  }
  return { kind: 'open-customer-service', corpId: support.corpId, url: support.url }
}
