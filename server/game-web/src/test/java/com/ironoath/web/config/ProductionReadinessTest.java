package com.ironoath.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.security.PlayerIdentityVerifier;

/**
 * 职责：prod 启动闸门的自检 —— 该拒绝的必须拒绝、该放行的绝不能拦（本类是纯逻辑单测，不起容器）。
 * 依赖：真实的 {@code contract/config} 表。
 *
 * <p><b>本类顺带钉住一条事实</b>：{@code SERVER_OPEN_AT} 与 {@code SEASON_START_AT} 都是
 * <b>不在表里的部署参数</b>，而身份校验默认装的是本地宽松实现，
 * 所以拿真实表跑出来的第一个结论就是「prod 现在起不来」。这正是想要的效果 ——
 * 上线检查清单里那几条「必须配置」以前只是一句人写的待办，现在它们是会拒绝启动的机械检查。
 */
class ProductionReadinessTest {

    private static ConfigRegistry plain;

    @BeforeAll
    static void loadTables() {
        plain = ConfigRegistry.loadFromDirectory(locateConfigDir());
    }

    /** 运维令牌已配置的守卫。部署参数类的凭据走 Environment，测试里就构造一个假的 Environment。 */
    private static com.ironoath.web.ops.OpsTokenGuard opsConfigured() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("ironoath.ops.token", "test-ops-token");
        return new com.ironoath.web.ops.OpsTokenGuard(env);
    }

    /** 什么都没配：这正是 prod 下的默认状态，而闸门的失败方向是「全关」不是「全开」。 */
    private static com.ironoath.web.ops.OpsTokenGuard opsMissing() {
        return new com.ironoath.web.ops.OpsTokenGuard(new org.springframework.mock.env.MockEnvironment());
    }

    /** 已接入真实登录会话的校验实现（prod 要求的就是这个）。 */
    private static PlayerIdentityVerifier identityReady() {
        return new PlayerIdentityVerifier() {
            @Override
            public Verdict verify(Claim claim) {
                return claim.token() != null ? Verdict.allow() : Verdict.deny("缺少会话票据");
            }

            @Override
            public boolean productionReady() {
                return true;
            }
        };
    }

    /** 本地宽松实现：任何声称都放行，因此 prod 必须因为它而拒绝启动。 */
    private static PlayerIdentityVerifier identityLenient() {
        return new com.ironoath.web.security.LocalDevIdentityVerifier();
    }

    /** 已接真实 code2session 的微信兑换器（prod 要求的那个）。 */
    private static com.ironoath.web.security.WeChatCodeExchanger weChatReady() {
        return new com.ironoath.web.security.WeChatCodeExchanger() {
            @Override
            public Identity exchange(String code) {
                return new Identity("openid-" + code, null, "session-key");
            }

            @Override
            public boolean productionReady() {
                return true;
            }
        };
    }

    @Test
    @DisplayName("prod + 真实表：四项全缺，四条都必须报出来（不是只报第一条）")
    void prodRefusesMissingDeploymentParams() {
        ProductionReadiness gate = new ProductionReadiness(plain,
                new GameProperties("contract/config", GameProperties.STORAGE_MONGO, false),
                opsMissing(), identityLenient(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "prod");

        assertThatThrownBy(gate::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod 启动被拒绝")
                .hasMessageContaining("SERVER_OPEN_AT")
                .hasMessageContaining("SEASON_START_AT")
                .hasMessageContaining("ironoath.ops.token")
                .hasMessageContaining("X-Player-Id")
                // 这一条用例里微信兑换器是"已接真实实现"的，所以问题仍是 4 项；
                // 兑换器缺失单独由 localWeChatExchangerAloneStillRefusesBoot 覆盖
                .hasMessageContaining("4 项");
    }

    @Test
    @DisplayName("只差微信兑换器还是本地实现：拒绝启动，并点名要配 AppID/AppSecret")
    void localWeChatExchangerAloneStillRefusesBoot() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(),
                new com.ironoath.web.security.LocalDevWeChatCodeExchanger(),
                contentSecurityReady(), paySignatureReady(), supportReady(), "prod");

        assertThat(gate.problems()).hasSize(1);
        assertThat(gate.problems().get(0))
                .contains("LocalDevWeChatCodeExchanger")
                .contains("WECHAT_APP_ID")
                .contains("WECHAT_APP_SECRET");
        assertThatThrownBy(gate::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    /** 内容安全已接真实实现（构造器只校验凭据非空，不会发起任何请求）。 */
    private static com.ironoath.web.security.ContentSecurityClient contentSecurityReady() {
        return new com.ironoath.web.security.WeChatContentSecurityClient("appid-for-test", "secret-for-test");
    }

    private static com.ironoath.web.service.PayAppService.SignatureVerifier paySignatureReady() {
        return new com.ironoath.web.service.PayAppService.SignatureVerifier() {
            @Override public boolean verify(com.ironoath.web.dto.generated.PayCallbackReq callback,
                    com.ironoath.core.pay.PayOrder order) { return true; }
            @Override public boolean productionReady() { return true; }
        };
    }

    @Test
    @DisplayName("只差内容安全还是本地放行实现：拒绝启动，并点名要配 AppID/AppSecret")
    void localContentSecurityAloneStillRefusesBoot() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(), weChatReady(),
                new com.ironoath.web.security.LocalDevContentSecurityClient(),
                paySignatureReady(), supportReady(), "prod");

        assertThat(gate.problems()).hasSize(1);
        assertThat(gate.problems().get(0))
                .contains("LocalDevContentSecurityClient")
                .contains("WECHAT_APP_ID")
                .contains("WECHAT_APP_SECRET");
        assertThatThrownBy(gate::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }
    /** 客服入口已配置（构造器只存字段，不发请求）。 */
    private static com.ironoath.web.release.SupportConfig supportReady() {
        return new com.ironoath.web.release.SupportConfig("corp-for-test", "https://work.weixin.qq.com/kf/test");
    }

    @Test
    @DisplayName("只差客服入口配置：拒绝启动，并点名要配 WECHAT_SUPPORT_*")
    void missingSupportConfigAloneStillRefusesBoot() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(), weChatReady(), contentSecurityReady(), paySignatureReady(),
                new com.ironoath.web.release.SupportConfig("", ""), "prod");

        assertThat(gate.problems()).hasSize(1);
        assertThat(gate.problems().get(0))
                .contains("WECHAT_SUPPORT_CORP_ID")
                .contains("WECHAT_SUPPORT_URL");
        assertThatThrownBy(gate::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }
    @Test
    @DisplayName("非 prod 一律放行：开发与单测的部署参数本来就是缺的，拦它等于拦自己")
    void nonProdNeverBlocks() {
        ProductionReadiness dev = new ProductionReadiness(plain,
                new GameProperties(null, GameProperties.STORAGE_MEMORY, true),
                opsMissing(), identityLenient(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "dev");
        assertThatCode(dev::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(new ProductionReadiness(plain, new GameProperties(null, "memory", false),
                opsMissing(), identityLenient(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "").isProd()).isFalse();
        // 逗号分隔的多 profile 也要认出来（Spring 允许 prod,mongo 这种写法）
        assertThat(new ProductionReadiness(plain, new GameProperties(null, "memory", false),
                opsMissing(), identityLenient(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "prod, mongo").isProd()).isTrue();
    }

    @Test
    @DisplayName("参数配齐 + mongo + 关 detail + 配好令牌 + 真身份实现：闸门必须开绿灯")
    void fullyConfiguredProdStarts() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "prod");

        assertThat(gate.problems()).as("四项都配齐后不能再拦，否则会挡住正常发布").isEmpty();
        assertThatCode(gate::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("生产禁用 legacy 回调后，「验签桩」不再是生产阻塞项 —— 但读数必须明说端点已禁")
    void prodNoLongerBlocksOnLegacySignatureBecauseTheEndpointIsGone() {
        // 这条用例是旧断言的<b>更正</b>，不是削弱：原来的断言（prod + 验签桩 ⇒ 拒绝启动）当时是对的 ——
        // legacy /pay/callback 在生产是活的，验签桩等于「任何人 POST 一下就发货」。
        // 裁决（2026-10-25 07:26:45.713Z）之后生产由 PayController 整体拒绝该端点，
        // 那段代码在生产根本到不了，再拦着就是逼部署的人去给一个不存在的入口接验签。
        //
        // 关键：这条不是「删掉断言」，是<b>把断言换到真正的防线上去</b> ——
        // 原来验的是「验签就绪」，现在验「端点已禁」这个事实本身有权威读数。
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        var paymentVerifier = new PayBeansConfig().paySignatureVerifier();
        assertThat(paymentVerifier.productionReady()).isFalse();
        var prodPolicy = PayBeansConfig.LegacyCallbackPolicy.forProfiles("prod");
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(), weChatReady(), contentSecurityReady(), paymentVerifier,
                supportReady(), "prod");

        assertThat(gate.problems())
                .as("生产下 legacy 端点已禁，验签桩不能再当阻塞项；实际 problems=%s", gate.problems())
                .noneSatisfy(problem -> assertThat(problem).contains("支付回调验签"));
        assertThatCode(gate::afterPropertiesSet)
                .as("除了这一条之外全部配齐，闸门必须开绿灯 —— 否则生产会被一个到不了的端点卡住")
                .doesNotThrowAnyException();
        assertThat(gate.legacyCallbackReading())
                .as("「不阻塞」不等于「不用管」：必须有一处权威读数说清端点在生产已禁")
                .isEqualTo(prodPolicy.rejectionReason())
                .contains("已在生产禁用");
    }

    @Test
    @DisplayName("只要 legacy 回调在那个环境还是活能力，验签桩就仍然是阻塞项（判据挂策略，不挂 prod）")
    void legacySignatureStaysABlockerWhereverTheEndpointIsStillLive() {
        // 上一条的<b>配对反证</b>：如果只留「prod 不报」那一条，把 problems() 里整段删掉也能全绿 ——
        // 那是假绿。这一条把同一个验签桩放到 dev 上：legacy 在那里还启用着，
        // 「POST 一下就发货」的风险是真实存在的，所以必须仍然报出来。
        // 两条合起来才证明判据落在 LegacyCallbackPolicy 上，而不是落在「现在是 prod」上。
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        var paymentVerifier = new PayBeansConfig().paySignatureVerifier();
        assertThat(PayBeansConfig.LegacyCallbackPolicy.forProfiles("dev").enabled())
                .as("前提：dev 下 legacy 必须还是启用的，否则这条用例测的东西不存在")
                .isTrue();
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(), weChatReady(), contentSecurityReady(), paymentVerifier,
                supportReady(), "dev");

        assertThat(gate.problems()).anySatisfy(problem -> assertThat(problem)
                .contains("支付回调验签")
                .contains("productionReady=false")
                .contains("在本环境启用"));
        // 非 prod 仍然放行启动（部署参数本来就缺），阻塞项的语义只在 prod 生效 —— 这条一起钉住，
        // 免得有人为了让上面那条报错而把「非 prod 直接 return」那行删掉
        assertThatCode(gate::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod 判定只有一份实现：闸门与装配层问的是同一个策略对象")
    void prodVerdictComesFromTheSamePolicyAsTheAssembler() {
        // isProd() 曾经自己切一遍 activeProfiles，与 LegacyCallbackPolicy.forProfiles 是两份同样的代码。
        // 两份代码的口径只要有一边变了（比如有人加 prod-eu），就会出现
        // 「闸门按生产拒绝、回调端点却放行」这种半生产状态 —— 那是最坏的形状：
        // 启动检查说这是生产，端点行为说这不是。
        for (String profiles : new String[]{"prod", "prod, mongo", " mongo , prod ", "dev", "test", "", "production"}) {
            var policy = PayBeansConfig.LegacyCallbackPolicy.forProfiles(profiles);
            ProductionReadiness gate = new ProductionReadiness(plain, new GameProperties(null, "memory", false),
                    opsMissing(), identityLenient(), weChatReady(), contentSecurityReady(),
                    paySignatureReady(), supportReady(), profiles);
            assertThat(gate.isProd())
                    .as("profile=[%s] 时闸门的 prod 判定必须与策略一致", profiles)
                    .isEqualTo(policy.prod());
            assertThat(gate.legacyCallbackEnabled()).as("profile=[%s]", profiles).isEqualTo(policy.enabled());
        }
        // 只认 prod 这一个字面量：production / prod-like 这类名字不算（否则「像生产」就成了生产）
        assertThat(PayBeansConfig.LegacyCallbackPolicy.forProfiles("production").prod()).isFalse();
        assertThat(PayBeansConfig.LegacyCallbackPolicy.forProfiles("prod-eu").prod()).isFalse();
    }

    @Test
    @DisplayName("正式 GameItem signer 缺少商户密钥或加密 session-key store 时必须阻止 prod")
    void missingGameItemSignerReadinessIsReportedAsAProdBlocker() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        var missingSigner = new com.ironoath.web.pay.MidasGameItemSigner(
                new org.springframework.mock.env.MockEnvironment(),
                new com.ironoath.web.security.WeChatSessionKeyStore() {
                    @Override public void put(String playerId, String sessionKey, long expiresAtMillis) {}
                    @Override public java.util.Optional<String> find(String playerId, long nowMillis) {
                        return java.util.Optional.empty();
                    }
                }, new ObjectMapper());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(), weChatReady(), contentSecurityReady(), paySignatureReady(),
                missingSigner, supportReady(), "prod");

        assertThat(gate.problems()).anySatisfy(problem -> assertThat(problem)
                .contains("GameItem")
                .contains("productionReady=false"));
        assertThatThrownBy(gate::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("GameItem readiness 要求 SAS ApplicationID 与两个环境的 BackendConfigID")
    void gameItemReadinessRequiresCallbackIdentityForBothEnvironments() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("ironoath.pay.app-key-prod", "test-production-app-key")
                .withProperty("ironoath.pay.app-key-sandbox", "test-sandbox-app-key")
                .withProperty("ironoath.pay.superapp-signature-secret", "test-secret-1234");
        var sessionKeys = new com.ironoath.web.security.WeChatSessionKeyStore() {
            @Override public void put(String playerId, String sessionKey, long expiresAtMillis) {}
            @Override public java.util.Optional<String> find(String playerId, long nowMillis) {
                return java.util.Optional.empty();
            }
            @Override public boolean productionReady() { return true; }
        };
        var signer = new com.ironoath.web.pay.MidasGameItemSigner(environment, sessionKeys, new ObjectMapper());

        assertThat(signer.productionReady()).isFalse();
        environment.setProperty("ironoath.pay.superapp-application-id", "test-sas-app");
        environment.setProperty("ironoath.pay.superapp-backend-config-id-prod", "test-prod-backend");
        environment.setProperty("ironoath.pay.superapp-backend-config-id-sandbox", "test-sandbox-backend");

        assertThat(signer.productionReady()).isTrue();
        assertThat(signer.verifyCallbackIdentity("test-sas-app", "test-prod-backend", "0")).isTrue();
        assertThat(signer.verifyCallbackIdentity("test-sas-app", "test-sandbox-backend", "1")).isTrue();
        assertThat(signer.verifyCallbackIdentity("test-sas-app", "test-prod-backend", "1")).isFalse();
        assertThat(signer.verifyCallbackIdentity("other-app", "test-prod-backend", "0")).isFalse();
    }

    /**
     * 判别性用例：前一条同时配齐了四项，所以它绿了并不能说明其中任何一条真的在生效。
     * 这里每次只拿掉一项，其余配好 —— 少一个变量，才能确认报错来自哪个变量。
     */
    @Test
    @DisplayName("其余全配好、只差运维令牌：仍然拒绝启动，且只报这一条")
    void missingOpsTokenAloneStillRefusesBoot() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsMissing(), identityReady(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "prod");

        assertThat(gate.problems()).as("只差令牌这一项时，报错列表里不能有别的项掩护").hasSize(1);
        assertThat(gate.problems().get(0)).contains("ironoath.ops.token").contains("赛季无人能结算");
        assertThatThrownBy(gate::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("只差身份校验还是宽松实现：仍然拒绝启动，且说的是「头等于没校验」这件事")
    void lenientIdentityAloneStillRefusesBoot() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityLenient(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "prod");

        assertThat(gate.problems()).hasSize(1);
        assertThat(gate.problems().get(0))
                .contains("X-Player-Id")
                .contains("LocalDevIdentityVerifier")
                .as("文案必须点出实现类名：让人一眼看出装的是哪个 bean，而不是去猜")
                .contains("微信登录");
        assertThatThrownBy(gate::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("存储用 memory、或 detail 开着：在生产里各是一条事故，必须点名")
    void dangerousSwitchesAreNamed() {
        ProductionReadiness memory = new ProductionReadiness(plain,
                new GameProperties(null, GameProperties.STORAGE_MEMORY, false),
                opsConfigured(), identityReady(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "prod");
        assertThat(memory.problems()).anySatisfy(problem -> assertThat(problem)
                .contains("ironoath.storage=memory").contains("丢档"));

        ProductionReadiness leaky = new ProductionReadiness(plain,
                new GameProperties(null, GameProperties.STORAGE_MONGO, true),
                opsConfigured(), identityReady(), weChatReady(), contentSecurityReady(), paySignatureReady(), supportReady(), "prod");
        assertThat(leaky.problems()).anySatisfy(problem -> assertThat(problem)
                .contains("expose-detail").contains("外挂"));
    }

    // ---------- 夹具 ----------

    /** 把两个部署参数追加进 global 表，模拟一次「部署时配齐了」的环境。 */
    private static String withDeploymentParams() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode table = (ObjectNode) mapper.readTree(Files.readString(
                    locateConfigDir().resolve("global.json"), StandardCharsets.UTF_8));
            ArrayNode rows = (ArrayNode) table.get("rows");
            for (String id : new String[]{"SERVER_OPEN_AT", "SEASON_START_AT"}) {
                ObjectNode row = mapper.createObjectNode();
                row.put("id", id);
                row.put("valueType", "LONG");
                row.put("value", 1_750_000_000_000L);
                row.put("unit", "毫秒时间戳");
                row.put("source", "测试注入：模拟部署时已配置");
                row.put("why", "本用例只验闸门，不验数值");
                rows.add(row);
            }
            return mapper.writeValueAsString(table);
        } catch (Exception e) {
            throw new IllegalStateException("无法构造带部署参数的 global 表", e);
        }
    }

    private static Path locateConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }
}
