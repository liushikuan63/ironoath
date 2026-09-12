package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.BotRegistry;

/**
 * 职责：钉住「合规闸门的判定来自哪张表」—— 注册表只做身份合成，口径住在 {@code BotTuning}（B11 §六 / §七）。
 *
 * <p><b>为什么单独一个类</b>：红线落在几条业务路径上（国家任命、议员席、赛季榜），
 * 那几条用例验的是"结果对不对"，但验不出"判定是不是同一份" ——
 * 比如有人把闸门改回"Bot 一律拒绝"，任命与议员席的用例照样绿，
 * 而 §六 明写 Bot 可以当小队普通成员：一刀切拒会误伤这类合法补位，
 * 拒窄了又会漏红线（只挡任命、挡不住派生席位）。这里直接问闸门本身，钉住"答案就是那张表"。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotRegistryComplianceTest {

    @Autowired private BotRegistry bots;

    @BeforeEach
    void reset() {
        bots.clear();
    }

    @Test
    @DisplayName("官职闸门的答案来自 §六 那张表：小队普通成员对 Bot 开放，队长/盟主/国家官职一律不给")
    void officeGateComesFromTheSectionSixTable() {
        String bot = "bot-rule-probe";
        bots.register(botProfile(bot));

        assertThat(bots.mayHoldOffice("human-1", "SQUAD", false, false))
                .as("闸门只约束 Bot：真人在任何层级都放行").isTrue();
        assertThat(bots.mayHoldOffice("human-1", "NATION", true, true)).isTrue();

        assertThat(bots.mayHoldOffice(bot, "SQUAD", false, false))
                .as("§六：小队可任普通成员 —— 一刀切拒绝会误伤这种合法补位").isTrue();
        assertThat(bots.mayHoldOffice(bot, "SQUAD", true, false))
                .as("队长位不给：一个 Bot 队长会替真人做「踢谁」这种组织决定").isFalse();
        assertThat(bots.mayHoldOffice(bot, "ALLIANCE", false, false)).isTrue();
        assertThat(bots.mayHoldOffice(bot, "ALLIANCE", true, false)).as("§七：不得任盟主").isFalse();
        assertThat(bots.mayHoldOffice(bot, "NATION", false, true)).as("§七：不得任任何国家官职").isFalse();
        assertThat(bots.mayHoldOffice(bot, "UNKNOWN", false, false)).as("未知层级默认拒绝").isFalse();
    }

    @Test
    @DisplayName("拒绝式形状带 BOT_NOT_ELIGIBLE 与可读文案；派生式形状返回 null 而不抛")
    void rejectShapeThrowsAndDerivedShapeReturnsNull() {
        String bot = "bot-rule-probe-2";
        bots.register(botProfile(bot));

        assertThatThrownBy(() -> bots.requireMayHoldOffice(bot, "NATION", false, true, "国家官职"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("国家官职")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BOT_NOT_ELIGIBLE);
        assertThatCode(() -> bots.requireMayHoldOffice("human-2", "NATION", false, true, "国家官职"))
                .as("真人一次也不能被这条闸门挡住").doesNotThrowAnyException();

        assertThat(bots.humanOnly(bot, "国家议员席"))
                .as("派生式形状：落选不是错误，所以返回 null 而不是抛").isNull();
        assertThat(bots.humanOnly("human-2", "国家议员席")).isEqualTo("human-2");
    }

    /** 与 {@code SeasonSettlementTest} / {@code NationEndpointTest} 同一组数字：红线只看身份，数值不影响任何断言。 */
    private static BotProfile botProfile(String botId) {
        return new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse("0.50"), FixedPoint.parse("0.50"),
                        FixedPoint.parse("0.50"), FixedPoint.parse("0.60")),
                new BotProfile.Persona(42L, 7L, 99L, List.of(12, 13, 20, 21, 22),
                        3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0"));
    }
}
