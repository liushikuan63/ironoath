package com.ironoath.web.config;

import com.ironoath.config.ConfigRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：生产环境的启动闸门 —— 该配的部署参数没配，就<b>拒绝启动</b>而不是静默 fail-open。
 * 依赖：game-config（读参数）、{@link GameProperties}（存储与 detail 开关）。
 *
 * <p><b>为什么必须是「起不来」而不是「起来后打一条 WARN」</b>：
 * {@code StartupReporter} 已经在打 WARN，而它跑在 {@code ApplicationReadyEvent} 上 ——
 * 那一刻网关早就开始往这个实例导流量了。fail-open 的开关一旦漏配，
 * 表现不是「报错」而是「行为悄悄地不对」：开服门槛全线放行、禁战期不存在、
 * 错误详情连着内部字段名一起下发给客户端。这些都要等到有人举报才发现，
 * 而发现时已经发出去的资源与已经泄漏的字段都收不回来。
 *
 * <p><b>一次列全所有问题</b>：与配置表校验同一条风格。改一个报一个会让部署的人
 * 反复试三轮，而每一次重启都在生产流量上留下一个空窗。
 */
@Component
public class ProductionReadiness implements InitializingBean {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ProductionReadiness.class);

    /** 需要显式配置的部署参数：id → 「不配会怎样」。 */
    private static final String[][] REQUIRED_PARAMS = {
            {"SERVER_OPEN_AT", "开服天数门槛（联盟 D3 / 国家 D14 等）会全部放行"},
            {"SEASON_START_AT", "备战期禁战、王城窗口、结算与休赛期全部不存在"},
    };

    private final ConfigRegistry configs;
    private final GameProperties properties;
    private final com.ironoath.web.ops.OpsTokenGuard ops;
    private final com.ironoath.web.security.PlayerIdentityVerifier identity;
    private final com.ironoath.web.security.WeChatCodeExchanger weChat;
    private final com.ironoath.web.security.ContentSecurityClient contentSecurity;
    private final com.ironoath.web.service.PayAppService.SignatureVerifier paySignature;
    private final com.ironoath.web.pay.MidasGameItemSigner gameItemSigner;
    private final com.ironoath.web.release.SupportConfig support;
    /** legacy 回调政策 —— 与 {@code PayController} 同一个 bean，见 {@link #problems()} 里那一条读数。 */
    private final PayBeansConfig.LegacyCallbackPolicy legacyCallback;
    /** 仅用于 {@link #isProd()} 委托时的兜底：{@code legacyCallback} 为 null 时按它判。 */
    private final String activeProfiles;

    @Autowired
    public ProductionReadiness(ConfigRegistry configs, GameProperties properties,
                               com.ironoath.web.ops.OpsTokenGuard ops,
                               com.ironoath.web.security.PlayerIdentityVerifier identity,
                               com.ironoath.web.security.WeChatCodeExchanger weChat,
                               com.ironoath.web.security.ContentSecurityClient contentSecurity,
                               com.ironoath.web.service.PayAppService.SignatureVerifier paySignature,
                               com.ironoath.web.pay.MidasGameItemSigner gameItemSigner,
                               com.ironoath.web.release.SupportConfig support,
                               PayBeansConfig.LegacyCallbackPolicy legacyCallback,
                               @Value("${spring.profiles.active:}") String activeProfiles) {
        this.configs = configs;
        this.properties = properties;
        this.ops = ops;
        this.identity = identity;
        this.weChat = weChat;
        this.contentSecurity = contentSecurity;
        this.paySignature = paySignature;
        this.gameItemSigner = gameItemSigner;
        this.support = support;
        this.legacyCallback = legacyCallback;
        this.activeProfiles = activeProfiles == null ? "" : activeProfiles;
    }

    /**
     * Compatibility constructor for readiness tests that predate the legacy-callback policy bean.
     *
     * <p>它们的调用方给出的是 profile 字符串（且带 GameItem signer），
     * 这里用 {@link PayBeansConfig.LegacyCallbackPolicy#forProfiles(String)} 就地折一个 ——
     * 走的仍是<b>同一个判定函数</b>，不是另写一套。保留它是因为这类用例验的是
     * 「GameItem signer 未就绪要拦 prod」，与被删掉的 10 参构造器是同一条断言链。
     */
    public ProductionReadiness(ConfigRegistry configs, GameProperties properties,
                               com.ironoath.web.ops.OpsTokenGuard ops,
                               com.ironoath.web.security.PlayerIdentityVerifier identity,
                               com.ironoath.web.security.WeChatCodeExchanger weChat,
                               com.ironoath.web.security.ContentSecurityClient contentSecurity,
                               com.ironoath.web.service.PayAppService.SignatureVerifier paySignature,
                               com.ironoath.web.pay.MidasGameItemSigner gameItemSigner,
                               com.ironoath.web.release.SupportConfig support,
                               String activeProfiles) {
        this(configs, properties, ops, identity, weChat, contentSecurity, paySignature, gameItemSigner, support,
                PayBeansConfig.LegacyCallbackPolicy.forProfiles(activeProfiles), activeProfiles);
    }

    /**
     * Compatibility constructor for isolated readiness tests predating the GameItem signer gate.
     *
     * <p>不注入 {@code legacyCallback} bean：调用方给出的是 profile 字符串，
     * 这里用 {@link PayBeansConfig.LegacyCallbackPolicy#forProfiles(String)} 就地折一个，
     * 走的仍是<b>同一个判定函数</b>，不是另写一套。
     */
    public ProductionReadiness(ConfigRegistry configs, GameProperties properties,
                               com.ironoath.web.ops.OpsTokenGuard ops,
                               com.ironoath.web.security.PlayerIdentityVerifier identity,
                               com.ironoath.web.security.WeChatCodeExchanger weChat,
                               com.ironoath.web.security.ContentSecurityClient contentSecurity,
                               com.ironoath.web.service.PayAppService.SignatureVerifier paySignature,
                               com.ironoath.web.release.SupportConfig support,
                               String activeProfiles) {
        this(configs, properties, ops, identity, weChat, contentSecurity, paySignature, null, support,
                PayBeansConfig.LegacyCallbackPolicy.forProfiles(activeProfiles), activeProfiles);
    }

    @Override
    public void afterPropertiesSet() {
        // 每次启动都打一条权威读数：生产里 legacy 端点是被禁的，而「被禁」这件事
        // 只体现在 PayController 的一个 if 上 —— 运维读日志时应该能直接看到结论，
        // 而不是去翻代码确认「这个环境到底还收不收 legacy 回调」。
        LOG.info("legacy /pay/callback 状态：{}", legacyCallbackReading());
        if (!isProd()) {
            return;
        }
        List<String> problems = problems();
        if (problems.isEmpty()) {
            return;
        }
        throw new IllegalStateException("prod 启动被拒绝：以下 " + problems.size()
                + " 项必须在部署时定下来（缺任何一项都是一次静默的行为事故，而不是一个可以被忽略的警告）\n  "
                + String.join("\n  ", problems));
    }

    /**
     * 是否按生产环境对待。
     *
     * <p><b>判据不在这里</b>：委托给 {@link PayBeansConfig.LegacyCallbackPolicy}——
     * 全仓唯一一处「什么算生产」的实现在 {@code LegacyCallbackPolicy.forProfiles}。
     * 此前这里自己切一遍 {@code activeProfiles}，与装配层那份是两份同样的代码，
     * 任何一边改口径都会让「闸门按生产拒绝、回调端点却放行」这种半生产状态悄悄成立。
     *
     * <p>注入的 policy 为 null（老的单测构造路径）时才就地折一个，折的仍是同一个函数。
     */
    boolean isProd() {
        if (legacyCallback != null) {
            return legacyCallback.prod();
        }
        return PayBeansConfig.LegacyCallbackPolicy.forProfiles(activeProfiles).prod();
    }

    /**
     * legacy {@code /pay/callback} 在本环境的真实状态 —— 读数，不是阻塞项。
     *
     * <p>为什么要单独给一条：生产环境里这个端点<b>已经被禁用</b>，
     * 所以「验签实现没生产就绪」不再是一条阻塞（那段代码在生产根本不会被调用）。
     * 但「不阻塞」不等于「不用管」—— 有人会问「那 /pay/callback 现在到底能不能用」，
     * 答案必须有一处权威读数，否则只能靠读 {@code PayController} 的 if 去猜。
     */
    String legacyCallbackReading() {
        if (legacyCallback == null || legacyCallback.enabled()) {
            return "legacy /pay/callback 在本环境启用：它不核验渠道签名的生产就绪性，只允许存在于开发/测试";
        }
        return legacyCallback.rejectionReason();
    }

    /**
     * legacy 回调在本环境是否仍是一门活能力（包内可见：闸门测试要钉住「判据只有一份」）。
     *
     * <p>注入的 policy 为 null（老的单测构造路径）时按 profile 字符串折一个 ——
     * 折的仍是 {@link PayBeansConfig.LegacyCallbackPolicy#forProfiles(String)}，不是另写一套。
     */
    boolean legacyCallbackEnabled() {
        if (legacyCallback != null) {
            return legacyCallback.enabled();
        }
        return PayBeansConfig.LegacyCallbackPolicy.forProfiles(activeProfiles).enabled();
    }

    /**
     * 逐条列出问题。非 prod 时也调用它（测试与自检用），所以它本身不判断环境。
     */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!GameProperties.STORAGE_MONGO.equals(properties.storage())) {
            problems.add("ironoath.storage=" + properties.storage()
                    + "：生产必须是 mongo，内存存储意味着重启即全服丢档");
        }
        if (properties.exposeDetail()) {
            problems.add("ironoath.expose-detail=true：错误详情含内部字段名与配置 id，"
                    + "下发给客户端等于给外挂作者送地图");
        }
        for (String[] entry : REQUIRED_PARAMS) {
            String id = entry[0];
            if (!configs.hasParam(id) || configs.longParam(id) <= 0L) {
                problems.add("全局参数 " + id + " 未配置或非正值：" + entry[1]);
            }
        }
        if (!ops.configured()) {
            // 这一条的失败方向与其余几条相反：其余是「漏配就放松」，这一条是「漏配就全关」。
            // 仍然必须在启动时炸 —— 全服赛季结算不了是发布当天才会被发现的问题，
            // 而那时的表现是运维的调度脚本收到一串 401，没人会想到是本服少配了一个环境变量
            problems.add("部署参数 " + com.ironoath.web.ops.OpsTokenGuard.TOKEN_PROPERTY
                    + " 未配置：运维端点（如 POST /season/settle）会全部拒绝，赛季无人能结算");
        }
        if (!identity.productionReady()) {
            // 这一条的分量比"少配一个参数"重：宽松的身份校验意味着**请求头里写谁的 playerId，
            // 系统就以谁的身份执行** —— 改别人的城、花别人的金币、看别人的战报全部成立，
            // 而且没有任何一处会报错。所以它必须是"起不来"，不能是"起来后打一条 WARN"。
            problems.add("身份校验实现是 " + identity.getClass().getSimpleName()
                    + "（productionReady=false）：X-Player-Id 头等于没有校验，"
                    + "必须接入微信登录会话校验（B15 §三）之后才能启动");
        }
        if (!weChat.productionReady()) {
            // 与身份校验同一族：本地兑换器把 code 哈希成 openid，等于"任何人报一串字符串
            // 就能变成某个微信账号"。而它同时还会让真实玩家永远登不进自己的号（openid 对不上），
            // 所以既不能上生产，也不能靠"跑起来再看看"发现。
            problems.add("微信登录兑换器是 " + weChat.getClass().getSimpleName()
                    + "（productionReady=false）：必须配置 WECHAT_APP_ID / WECHAT_APP_SECRET "
                    + "并切到真实 code2session 实现之后才能启动");
        }
        if (!contentSecurity.productionReady()) {
            // 与上面两条同一族，但分量不同：这一条不是「谁能进门」，是「玩家的内容有没有被送检」。
            // 本地放行实现上了生产，表现是一**切照常、没有任何一处报错**，直到有人拿违规内容
            // 截图举报 —— 那种事不会在联调里被发现，所以它必须是「起不来」。
            problems.add("内容安全实现是 " + contentSecurity.getClass().getSimpleName()
                    + "（productionReady=false）：昵称/小队名/联盟名/聊天都不会真的送检，"
                    + "必须配置 WECHAT_APP_ID / WECHAT_APP_SECRET 并切到真实 msg_sec_check 实现之后才能启动");
        }
        // 这一条历史上是「验签实现 productionReady=false ⇒ 当前 /pay/callback 不能核验渠道签名，
        // 必须接入正式米大师验签后才能启动」。当时成立：legacy /pay/callback 在生产是活的，
        // 验签桩等于「任何人 POST 一下就能给自己发货」。
        //
        // 裁决（2026-10-25 07:26:45.713Z）之后，生产由 PayController 整体拒绝该端点，
        // 判据是 {@link PayBeansConfig.LegacyCallbackPolicy}（本类的 isProd() 也委托它，
        // 所以闸门与端点在结构上不可能各说各话）。于是 prod 下这条阻塞项成了**假阻塞**：
        // 它逼着部署的人去给一个生产根本到不了的端点接验签，而真正的防线（端点已拒绝）没人看。
        //
        // 但它不能直接删：判据挂在策略上，而不是挂在「现在是 prod」上 ——
        // 只要那个环境里 legacy 还是一门活能力，未就绪的验签就仍然是「POST 一下就发货」。
        // 这样将来若有人放开策略，这条阻塞会自动回来，不需要谁记得来改这里。
        if (legacyCallbackEnabled() && !paySignature.productionReady()) {
            problems.add("支付回调验签实现是 " + paySignature.getClass().getSimpleName()
                    + "（productionReady=false）：legacy /pay/callback 在本环境启用却不能核验渠道签名，"
                    + "必须接入正式米大师验签后才能启动");
        }
        if (gameItemSigner != null && !gameItemSigner.productionReady()) {
            problems.add("GameItem 支付配置未就绪（productionReady=false）：需要正式与沙箱 AppKey、TC-Signature 加密密钥、SAS ApplicationID、正式/沙箱 BackendConfigID 及加密 session_key 共享存储后才能启动");
        }
        if (!support.configured()) {
            // 这一条与「运维令牌」同一族：漏配不是"放松校验"，是"入口变成死按钮"。
            // 提审会查客服与退款入口（上线检查清单 §二 8/9），而那两项靠的就是这份配置 ——
            // 让它在启动时炸，比让审核员点出一个「未配置」要好。
            problems.add("客服/退款入口未配置（WECHAT_SUPPORT_CORP_ID / WECHAT_SUPPORT_URL）："
                    + "设置页会显示入口但点下去只会说明未配置，而提审会查这一项");
        }
        return List.copyOf(problems);
    }
}
