package com.ironoath.web.security;

/**
 * 职责：判断「这个请求/连接声称自己是哪个玩家」是否成立（B15 §三 合规链路的入口）。
 * 依赖：无（纯接口）。
 *
 * <p><b>为什么是一个端口而不是在拦截器里写 {@code if (dev)}</b>：与支付验签同一个理由
 * （见 {@code PayAppService.SignatureVerifier}）。分支版本的危险在于「配置漏一项就静默退化成不校验」，
 * 而不校验的身份头等于任何人换个 playerId 就能操作别人的城、花别人的金币。
 * 端口版本要求正式环境<b>必须</b>提供一个真实现，否则启动直接被拒
 * （{@code ProductionReadiness} 会因 {@link #productionReady()} 为 false 而拒绝启动）。
 *
 * <p><b>真实实现要做什么</b>：客户端登录时拿 {@code wx.login} 的 code 换 openid + session_key，
 * 服务端换取一个会话票据；之后每个请求带上该票据，实现里核对该票据 ↔ playerId 是否同一会话，
 * 并顺带把实名/年龄结果带出来给 {@code MINOR_PAY_*} 两个限额用（收口清单 #26 ④）。
 * <b>这一半已经写完了</b>：{@code AuthSessionService}（HMAC 票据）+ {@code SessionIdentityVerifier}
 * （{@code productionReady=true}）都在仓库里，由 {@code SecurityBeansConfig} 在有微信凭据时装配。
 * 本机验不了的只有 {@code code2session} 那一步（没有真实 AppID/密钥），不是整条鉴权链。
 *
 * <p><b>票据怎么带</b>：客户端每个 HTTP 请求带标准头 {@code Authorization: Bearer <token>}，
 * {@code PlayerIdentityVerifier.TOKEN_HEADER} 只是同一条读取路径上的兼容回退
 * （见 {@code PlayerIdentityInterceptor#tokenOf}：先 Bearer，再自定义头）。
 */
public interface PlayerIdentityVerifier {

    /**
     * 会话票据的兼容头名。<b>标准头 {@code Authorization: Bearer} 优先</b> ——
     * 客户端（{@code NetModule}）每个请求带的是那个，这里留着是给早期联调脚本用。
     */
    String TOKEN_HEADER = "X-Auth-Token";

    /**
     * 校验一次声称。
     *
     * @param claim 声称的身份与随行的票据
     * @return 通过与否；不通过时带一句能直接给玩家看的原因
     */
    Verdict verify(Claim claim);

    /**
     * 这个实现能否用于正式环境。<b>默认 false</b>：新加的实现必须显式声明自己可以，
     * 于是「随手写一个宽松的校验器」在 prod 下会让服务起不来，而不是悄悄上线一个假闸门。
     */
    default boolean productionReady() {
        return false;
    }

    /**
     * @param playerId 请求声称的玩家 id（来自 {@code X-Player-Id}）
     * @param token    随行票据；无登录体系时为 null
     * @param uri      请求路径，供实现做「哪些端点允许弱身份」的判断（WebSocket bind 时为连接标识）
     */
    record Claim(String playerId, String token, String uri) {
    }

    /** 校验结论。 */
    record Verdict(boolean allowed, String reason) {

        public static Verdict allow() {
            return new Verdict(true, null);
        }

        public static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }
}
