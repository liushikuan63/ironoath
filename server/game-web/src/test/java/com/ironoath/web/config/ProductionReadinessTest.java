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
                opsMissing(), identityLenient(), weChatReady(), "prod");

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
                new com.ironoath.web.security.LocalDevWeChatCodeExchanger(), "prod");

        assertThat(gate.problems()).hasSize(1);
        assertThat(gate.problems().get(0))
                .contains("LocalDevWeChatCodeExchanger")
                .contains("WECHAT_APP_ID")
                .contains("WECHAT_APP_SECRET");
        assertThatThrownBy(gate::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("非 prod 一律放行：开发与单测的部署参数本来就是缺的，拦它等于拦自己")
    void nonProdNeverBlocks() {
        ProductionReadiness dev = new ProductionReadiness(plain,
                new GameProperties(null, GameProperties.STORAGE_MEMORY, true),
                opsMissing(), identityLenient(), weChatReady(), "dev");
        assertThatCode(dev::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(new ProductionReadiness(plain, new GameProperties(null, "memory", false),
                opsMissing(), identityLenient(), weChatReady(), "").isProd()).isFalse();
        // 逗号分隔的多 profile 也要认出来（Spring 允许 prod,mongo 这种写法）
        assertThat(new ProductionReadiness(plain, new GameProperties(null, "memory", false),
                opsMissing(), identityLenient(), weChatReady(), "prod, mongo").isProd()).isTrue();
    }

    @Test
    @DisplayName("参数配齐 + mongo + 关 detail + 配好令牌 + 真身份实现：闸门必须开绿灯")
    void fullyConfiguredProdStarts() {
        ConfigRegistry configured = ConfigRegistry.loadFromDirectory(locateConfigDir());
        configured.reload("global", com.ironoath.config.model.GlobalCfg.class, withDeploymentParams());
        ProductionReadiness gate = new ProductionReadiness(configured,
                new GameProperties(null, GameProperties.STORAGE_MONGO, false),
                opsConfigured(), identityReady(), weChatReady(), "prod");

        assertThat(gate.problems()).as("四项都配齐后不能再拦，否则会挡住正常发布").isEmpty();
        assertThatCode(gate::afterPropertiesSet).doesNotThrowAnyException();
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
                opsMissing(), identityReady(), weChatReady(), "prod");

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
                opsConfigured(), identityLenient(), weChatReady(), "prod");

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
                opsConfigured(), identityReady(), weChatReady(), "prod");
        assertThat(memory.problems()).anySatisfy(problem -> assertThat(problem)
                .contains("ironoath.storage=memory").contains("丢档"));

        ProductionReadiness leaky = new ProductionReadiness(plain,
                new GameProperties(null, GameProperties.STORAGE_MONGO, true),
                opsConfigured(), identityReady(), weChatReady(), "prod");
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
