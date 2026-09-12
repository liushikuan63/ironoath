package com.ironoath.web.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;

/**
 * 职责：只应由运维/调度系统调用的端点的鉴权（B14 {@code POST /season/settle} 是第一个使用者）。
 * 依赖：Spring 的 {@link Environment}（读部署参数），零业务依赖。
 *
 * <p><b>为什么需要它</b>：赛季结算是「外部调度系统按时钟打一次」的动作，不是玩家动作。
 * 没有这道闸门时，任何人都能反复 POST 它。阶段门 + 幂等键确实把最坏后果限制成「白跑一次」，
 * 但那是两道*业务*闸门在替一道*身份*闸门补课 —— 业务规则一改（比如结算期放宽、幂等键换算法），
 * 这个端点就会悄悄变成对外可写的口子，而那时没有任何东西会报错。
 *
 * <p><b>fail-closed</b>：服务端没有配令牌时一律拒绝，而不是放行。
 * 「忘了配 = 谁都进不来」是运维能立刻发现的故障，
 * 「忘了配 = 谁都能进来」是永远不会有人来报的事故。
 *
 * <p>令牌是凭据，所以走 {@link Environment}（application-*.yml + IRONOATH_* 环境变量）
 * 而不是 {@code contract/config}：配置表会进版本库。与 {@code PayAppService} 读 offerId 同一口径。
 */
@Component
public class OpsTokenGuard {

    /** 请求头名。与 {@code X-Player-Id} 一样是显式的对外契约，改名等于让调度系统全部 401。 */
    public static final String HEADER = "X-Ops-Token";

    private static final Logger LOG = LoggerFactory.getLogger(OpsTokenGuard.class);

    /** 部署参数名。生产必须配（见 {@code ProductionReadiness}），本地开发用 --ironoath.ops.token=... 传。 */
    public static final String TOKEN_PROPERTY = "ironoath.ops.token";

    private final Environment environment;

    public OpsTokenGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * 校验一次调用。不通过就抛 {@link ErrorCode#OPS_UNAUTHORIZED}。
     *
     * <p><b>不区分「令牌错」与「服务端没配令牌」</b>给调用方看：前者要发给运维查凭据下发，
     * 后者是本服的配置故障 —— 两者的 detail 与日志都不同，但都只回一句「鉴权未通过」给调用方，
     * 免得这个端点变成一个可以探测「服务端配没配凭据」的探针。
     *
     * <p>比对用 {@link MessageDigest#isEqual}（定长时间比较）。用 {@code String.equals} 的话
     * 比较会在第一个不同的字节处返回，于是响应时间随「猜对了前几个字符」变长 ——
     * 一个内部端点值不值得防这个另说，但这行代码不比别的长，没有理由写错的那版。
     */
    public void require(String presentedToken) {
        String expected = environment.getProperty(TOKEN_PROPERTY, "");
        if (expected.isBlank()) {
            LOG.error("运维端点被调用，但本服没有配置 {}：已按拒绝处理（fail-closed）。"
                    + "生产环境这是上线清单上的硬阻塞项，本地开发用 --{}=... 传入",
                    TOKEN_PROPERTY, TOKEN_PROPERTY);
            throw new BizException(ErrorCode.OPS_UNAUTHORIZED,
                    "服务端未配置运维令牌（" + TOKEN_PROPERTY + "），运维端点一律拒绝");
        }
        if (presentedToken == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedToken.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("运维端点鉴权失败：令牌不匹配（请求头 {}）。已拒绝", HEADER);
            throw new BizException(ErrorCode.OPS_UNAUTHORIZED, "运维令牌不匹配");
        }
    }

    /** 是否已配置令牌。只给启动自检用，不参与请求路径判定。 */
    public boolean configured() {
        return !environment.getProperty(TOKEN_PROPERTY, "").isBlank();
    }
}
