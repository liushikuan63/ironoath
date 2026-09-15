package com.ironoath.web.security;

/**
 * 职责：把客户端 {@code wx.login} 的临时 code 换成微信账号身份（B15 §三）。
 * 依赖：无（纯接口）。
 *
 * <p><b>为什么是端口而不是直接调 HTTP</b>：与 {@link PlayerIdentityVerifier} 同一条理由 ——
 * 本地开发、单测、联调都必须能在没有 AppSecret 的环境里跑通整条登录链路，
 * 而生产环境必须真的去调微信。分支版本（if dev）的问题在于漏配置会静默退化成"谁都放行"，
 * 端口版本则由 {@code ProductionReadiness} 检查实现是否声明可用于生产。
 *
 * <p><b>微信侧的约束（写在这里，接真实现时不用再翻文档）</b>：
 * <ul>
 *   <li>code 只能用一次，5 分钟内有效；</li>
 *   <li>同一个 code 换两次，第二次返回 {@code 40163 code been used}；</li>
 *   <li>{@code openid} 是同一个用户在同一个 AppID 下的唯一标识 —— 它就是我们的账号键；</li>
 *   <li>{@code session_key} <b>不得下发到客户端</b>，只用于服务端解密敏感数据（本实现先不用它）。</li>
 * </ul>
 */
public interface WeChatCodeExchanger {

    /**
     * 微信账号键的前缀：{@code PlayerSave.deviceId} 存 {@code wx:<openid>} 时，这条存档属于一个微信账号。
     *
     * <p>常量住在端口上而不是散在调用处：账号键的形状由本端口定义，
     * 认证之外还要读它的地方（例如内容安全要从账号键里取 openid 送检）都从这里取，
     * 免得哪天改了前缀，只有一半的代码知道。
     */
    String WECHAT_ACCOUNT_PREFIX = "wx:";

    /**
     * 用临时 code 换微信身份。
     *
     * @param code 客户端 {@code wx.login} 拿到的 code
     * @return 微信返回的身份信息
     * @throws WeChatLoginException 换取失败（code 过期/被用过/网络异常/微信返回错误码）
     */
    Identity exchange(String code);

    /**
     * 这个实现能否用于正式环境。<b>默认 false</b>：本地实现必须显式声明不能上生产，
     * 于是"忘了配 AppSecret 就上线"的表现是服务起不来，而不是所有人都能登进别人的号。
     */
    default boolean productionReady() {
        return false;
    }

    /**
     * 微信身份。<b>sessionKey 不进日志、不进响应、不进任何 DTO</b> ——
     * 它是解开加密数据（手机号、用户信息）的钥匙，泄露等于拿到该用户的微信侧凭据。
     */
    record Identity(String openId, String unionId, String sessionKey) {
    }
}
