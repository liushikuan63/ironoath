/**
 * 职责：隐私授权的判定（上线检查清单 §二 5：首次启动弹出并需同意、设置页可随时查看）。
 * 依赖：无（纯函数，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>为什么走微信原生流程而不是自建弹窗</b>：协议文本要法务出（§二 5 明写「协议文本（需法务）」），
 * 而微信自己那套（`getPrivacySetting` / `requirePrivacyAuthorize` / `openPrivacyContract`）
 * 用的是公众平台上配置的《用户隐私保护指引》—— 文本在平台侧维护、弹窗由平台绘制、同意由平台记录。
 * 自建一套等于把同一份协议维护两遍，而两遍迟早不一致，**不一致在提审时是要被打回的**。
 *
 * <p><b>本模块只回答两个问题</b>：这次启动要不要发起授权、设置页那一行该显示什么。
 * 它不做任何合规判断（那是法务与平台的事），也不缓存"同意过没有"——
 * 微信返回的 `needAuthorization` 就是权威答案，自己再记一份只会在两边不一致时多一个要解释的状态。
 */

/** 微信 `wx.getPrivacySetting` 返回值里我们真正用到的两个字段。 */
export interface PrivacySettingLike {
  /** 是否还需要用户授权；false 表示平台认为已同意（或本账号无需授权） */
  readonly needAuthorization: boolean
  /** 平台配置的协议名，例如《用户隐私保护指引》；平台没给时为 null */
  readonly privacyContractName?: string | null
}

/** 这次启动的隐私动作计划。 */
export interface PrivacyPlan {
  /** 是否发起授权（true 时由平台弹它自己的协议弹窗，本作不画） */
  readonly request: boolean
  /** 协议名（平台给的原文）；拿不到时为 null，界面据此走"查看协议"的兜底文案 */
  readonly contractName: string | null
  /** 运行环境是否提供隐私接口 —— 浏览器/编辑器预览为 false，日志与设置页都要能看出这一点 */
  readonly apiAvailable: boolean
}

/**
 * 按平台返回的隐私设置决定这次启动做什么。
 *
 * @param setting 查询结果；null 表示没查成（接口缺失或调用失败）
 */
export function planPrivacyPrompt(setting: PrivacySettingLike | null): PrivacyPlan {
  if (setting === null) {
    // 查不到就不弹：把启动卡在一次失败的查询上，比"这次没弹"糟得多，
    // 而"这次没弹"在下一次启动会被平台重新问一遍（needAuthorization 始终是权威答案）。
    return { request: false, contractName: null, apiAvailable: false }
  }
  return {
    request: setting.needAuthorization === true,
    contractName: setting.privacyContractName ?? null,
    apiAvailable: true,
  }
}
