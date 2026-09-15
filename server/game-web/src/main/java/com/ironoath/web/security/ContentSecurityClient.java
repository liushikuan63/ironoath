package com.ironoath.web.security;

/**
 * 职责：玩家可自由填写内容的合规送检（微信 {@code msg_sec_check}，B15 §3 / 上线检查清单 §二 7）。
 * 依赖：无（纯接口）。
 *
 * <p><b>为什么是端口而不是直接调 HTTP</b>：与 {@link WeChatCodeExchanger} 同一条理由 ——
 * 本地开发、单测、联调都必须能在没有 AppSecret、也连不上微信的环境里跑通整条链路；
 * 而生产必须真的送检。分支版本（if dev）的问题是漏配置会静默退化成"什么都放行"，
 * 端口版本则由 {@code ProductionReadiness} 检查实现是否声明可用于生产。
 *
 * <p><b>这件事覆盖哪四处</b>（上线检查清单 §二 7 点名的四个面）：昵称、小队名、联盟名、聊天内容。
 * 前三个在微信侧算"资料"（scene=1），聊天算"社交日志"（scene=4）。
 *
 * <p><b>微信侧的约束（写在这里，接真实现时不用再翻文档）</b>：
 * <ul>
 *   <li>必须带 {@code openid} —— 没有 openid 就没有送检对象，所以本端口只对微信账号有意义；</li>
 *   <li>要先拿 {@code access_token}（appid + secret 换，有效期约 2 小时，需缓存）；</li>
 *   <li>返回 {@code errcode=87014} 表示"内容 risky"，这就是要拒的那一档；</li>
 *   <li>{@code 40001}/{@code 42001} 是 token 失效，要换一次 token 再试（不是内容问题）。</li>
 * </ul>
 */
public interface ContentSecurityClient {

    /** 送检场景。取值就是微信 API 的 {@code scene} 数字，别在这里自造一套语义。 */
    enum Scene {
        /** 资料：昵称、小队名、联盟名。 */
        PROFILE(1),
        /** 社交日志：聊天内容。 */
        SOCIAL_LOG(4);

        private final int code;

        Scene(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /**
     * 送检一段内容。
     *
     * <p>返回值刻意分成三档而不是布尔：{@code ALLOWED}（微信说没问题）、{@code RISKY}（微信说内容违规）、
     * {@code UNAVAILABLE}（**没问成**：没有 openid、网络不通、微信返回了非内容类错误）。
     * 把后两者并成一个 {@code false} 会让"微信挂了"看起来像"玩家发了违规内容"，
     * 而那两件事该有完全不同的处置（一个有日志有计数、一个要拒绝并提示玩家）。
     */
    Verdict check(String openId, Scene scene, String content);

    /**
     * 这个实现能否用于正式环境。<b>默认 false</b>：本地实现必须显式声明不能上生产，
     * 于是"忘了配 AppSecret 就上线"的表现是服务起不来，而不是全服内容无人送检。
     */
    default boolean productionReady() {
        return false;
    }

    /** 送检结论。 */
    enum Verdict {
        /** 微信判定通过。 */
        ALLOWED,
        /** 微信判定内容违规（{@code 87014}）—— 调用方要拒绝并给出提示，不是静默替换。 */
        RISKY,
        /** 这次没问成（无 openid / 网络异常 / 非内容类错误码）。调用方按既定取舍处置。 */
        UNAVAILABLE,
    }
}
