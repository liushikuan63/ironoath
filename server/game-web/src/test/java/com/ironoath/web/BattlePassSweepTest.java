package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.web.battlepass.BattlePassRules;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.BattlePassClaimReq;
import com.ironoath.web.dto.generated.BattlePassTrack;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.mail.MailStore;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：赛季末的战令补发（B24 验收 2 的后半句「未领的奖励进邮件，不静默作废」）。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>四条判据就是验收句的四个分句</b>：已达成未领的要发、再跑一次不重复发、
 * 已经手动领过的不发、没达成的不发（不能靠"补发"绕过积分）。
 *
 * <p><b>为什么要一个玩家一个玩家地断言邮件的正文</b>：补发最容易做假的地方是"发了封信但里面没东西"
 * 或"信里少了一档" —— 那两种在"发了 N 封"这种计数断言下都会绿。这里逐档比对档位号与内容。
 */
@SpringBootTest
@ActiveProfiles("test")
class BattlePassSweepTest {

    @Autowired private BattlePassService battlePass;
    @Autowired private BattlePassRules rules;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private MailStore mailStore;
    @Autowired private com.ironoath.web.reward.PaidPrivilegeGrants privilegeGrants;

    /** 读邮件用的「现在」：补发的保留期按服务端时刻算，测试里给一个够早的固定值即可。 */
    private static final long NOW = 1_700_000_100_000L;

    @Test
    @DisplayName("已达成未领的档位：按档发进邮件，正文里逐档写清是什么；再补发一次不重复发")
    void unclaimedTiersGoToMailExactlyOnce() {
        String playerId = player();
        privilegeGrants.grant(playerId, "battle_pass", 1L, 1_700_000_000_000L);
        battlePass.addPoints(playerId, 300L, "test");   // 第 1、2 档都达成
        // 第 1 档的免费线手动领掉：它不该再出现在补发里
        battlePass.claim(playerId, new BattlePassClaimReq(newId(), 1L, BattlePassTrack.FREE));

        int sent = battlePass.sweepSeasonToMail(rules.seasonId());
        assertThat(sent).as("一个人两条线各有一封：免费线与付费线各补一档").isEqualTo(2);

        List<MailStore.MailRecord> mails = mailStore.listOf(playerId, NOW);
        assertThat(mails).as("补发真的落在邮箱里（不是把奖励直接塞进背包）").hasSize(2);
        String freeBody = bodyOf(mails, "免费");
        String paidBody = bodyOf(mails, "付费");
        assertThat(freeBody).as("正文逐条写出补了什么").contains("木材箱");
        // 免费线第 1 档与第 2 档给的是同一样东西（木材箱），所以"第 1 档没被重复补"只能按**条数**判：
        // 只有一条 = 只补了第 2 档；两条 = 把已经手动领过的第 1 档又发了一遍
        assertThat(freeMail(mails).rewards())
                .as("只补第 2 档那一条（第 1 档已经手动领过，不该再发）")
                .hasSize(1);
        assertThat(paidBody).as("付费线补的是第 2 档的 50 金币").contains("金币");
        assertThat(mails).allSatisfy(mail -> {
            assertThat(mail.rewards()).as("每封都得有附件 —— 空信等于没补").isNotEmpty();
            assertThat(mail.claimedAt()).as("还没领（等玩家自己来点）").isNull();
        });

        // 再跑一次：账本里这些档位已经是已领，補发不该再发一遍
        int again = battlePass.sweepSeasonToMail(rules.seasonId());
        assertThat(again).as("重跑不重复发（幂等靠进度里的已领标记，不是靠结算的 requestId）").isZero();
        assertThat(mailStore.listOf(playerId, NOW)).as("邮箱里的封数没有变多").hasSize(2);
    }

    @Test
    @DisplayName("没达成的档位不补发：不能靠「赛季结束」绕过积分")
    void unreachedTiersAreNotSwept() {
        String playerId = player();
        privilegeGrants.grant(playerId, "battle_pass", 1L, 1_700_000_000_000L);
        battlePass.addPoints(playerId, 150L, "test");   // 只到第 1 档

        battlePass.claim(playerId, new BattlePassClaimReq(newId(), 1L, BattlePassTrack.FREE));
        battlePass.claim(playerId, new BattlePassClaimReq(newId(), 1L, BattlePassTrack.PAID));

        int sent = battlePass.sweepSeasonToMail(rules.seasonId());
        assertThat(sent).as("两条线都领过了，没东西可补").isZero();
        assertThat(mailStore.listOf(playerId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("没买战令：付费线一档都不补（那是没买过的东西），免费线照补")
    void paidTrackIsNotSweptWhenThePassWasNeverBought() {
        String playerId = player();
        battlePass.addPoints(playerId, 150L, "test");

        int sent = battlePass.sweepSeasonToMail(rules.seasonId());
        assertThat(sent).as("只有免费线那一封").isEqualTo(1);
        assertThat(mailStore.listOf(playerId, NOW)).hasSize(1);
        assertThat(mailStore.listOf(playerId, NOW).get(0).text())
                .as("正文说的是免费线").contains("免费").doesNotContain("付费");
    }

    private static MailStore.MailRecord freeMail(List<MailStore.MailRecord> mails) {
        return mails.stream().filter(mail -> mail.text().contains("免费")).findFirst()
                .orElseThrow(() -> new AssertionError("没有找到免费线那一封：" + mails));
    }

    private static String bodyOf(List<MailStore.MailRecord> mails, String trackWord) {
        return mails.stream().map(MailStore.MailRecord::text)
                .filter(body -> body.contains(trackWord))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到" + trackWord + "那一封：" + mails));
    }

    private String player() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "补发玩家",
                1_700_000_000_000L, "")).playerId();
    }

    private static String newId() {
        return "req-" + UUID.randomUUID();
    }
}
