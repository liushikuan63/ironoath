package com.ironoath.core.pay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.player.PlayerGiftPopup;
import com.ironoath.core.player.PlayerSave;

/**
 * 职责：礼包弹窗频控状态在「存档 ↔ 判定器」之间的往返（B19 S3-ii 的承载层）。
 * 依赖：game-core 纯 Java，不起容器。
 *
 * <p><b>为什么必须有它</b>：承载层单独落地时没有任何写入口（事件源与端点在同一格），
 * 所以它不能等端点的用例替它作证。这里两个方向钉住它：① 弹过两次的计数**跨重启**仍生效；
 * ② 快照**必须把触发时刻原样带回**，否则"读一次弹窗就抹掉触发"，礼包永远弹不出来。
 */
class PlayerGiftPopupRoundTripTest {

    private static final long HOUR = 3_600_000L;
    private static final long MINUTE = 60_000L;

    private static PopupThrottle.Rules rules() {
        return new PopupThrottle.Rules(24 * HOUR, 3, 10 * MINUTE);
    }

    @Test
    @DisplayName("重启后仍记得弹过几次：判定器从存档回灌，同一礼包的 24h 上限照旧生效")
    void showsSurviveARestart() {
        long now = 1_788_000_000_000L;
        PlayerGiftPopup stored = PlayerGiftPopup.empty();

        for (int i = 0; i < 2; i++) {
            PopupThrottle run = PopupThrottle.forPlayer(rules(), "p1", stored, 0L);
            assertThat(run.shouldShow("p1", "popup_stuck_supply", false, now + i * 11 * MINUTE).allowed())
                    .as("第 " + (i + 1) + " 次应当允许（间隔要大于 10 分钟全局冷却）").isTrue();
            stored = run.snapshotOf("p1", now + i * 11 * MINUTE)
                    .withShown("popup_stuck_supply", now + i * 11 * MINUTE);
        }
        assertThat(stored.showsOf("popup_stuck_supply")).hasSize(2);

        PopupThrottle restarted = PopupThrottle.forPlayer(rules(), "p1", stored, 0L);
        long third = now + 22 * MINUTE;
        assertThat(restarted.shouldShow("p1", "popup_stuck_supply", false, third).allowed())
                .as("上限是 3 次，弹过两次之后第三次仍该允许").isTrue();
        stored = restarted.snapshotOf("p1", third).withShown("popup_stuck_supply", third);

        PopupThrottle again = PopupThrottle.forPlayer(rules(), "p1", stored, 0L);
        PopupThrottle.Verdict verdict = again.shouldShow("p1", "popup_stuck_supply", false, now + 33 * MINUTE);
        assertThat(verdict.allowed()).as("存档里记得弹过三次 ⇒ 重启也不该重来").isFalse();
        assertThat(verdict.reason()).contains("同一礼包");
    }

    @Test
    @DisplayName("快照必须把触发时刻带回：读一次弹窗不得把触发抹掉（抹掉 = 礼包再也不弹）")
    void snapshotCarriesTriggersBack() {
        long now = 1_788_000_000_000L;
        PlayerGiftPopup stored = PlayerGiftPopup.empty().withTriggered("STUCK_STAGE", now - MINUTE);

        PlayerGiftPopup after = PopupThrottle.forPlayer(rules(), "p1", stored, 0L).snapshotOf("p1", now);

        assertThat(after.triggeredAtOf("STUCK_STAGE"))
                .as("事件源写在别的请求里，弹出记账这一路清空它 ⇒ 下一次 GET 再也看不到触发")
                .isEqualTo(now - MINUTE);
    }

    @Test
    @DisplayName("旧快照的窗口外时刻会被丢掉，且有界：每个礼包最多留 8 次")
    void snapshotPrunesAndStaysBounded() {
        long now = 1_788_000_000_000L;
        PlayerGiftPopup stored = PlayerGiftPopup.empty();
        for (int i = 0; i < 12; i++) {
            stored = stored.withShown("popup_stuck_supply", now - 30 * HOUR + i * MINUTE);
        }
        assertThat(stored.showsOf("popup_stuck_supply"))
                .as("有界：不封顶就是一条随会话数无限长大的存档字段")
                .hasSize(PlayerGiftPopup.SHOWS_PER_GIFT_MAX);

        assertThat(PopupThrottle.forPlayer(rules(), "p1", stored, 0L).snapshotOf("p1", now)
                .showsOf("popup_stuck_supply"))
                .as("24h 窗口外的时刻在快照时被丢掉").isEmpty();
    }

    @Test
    @DisplayName("存档槽位：老档读成空状态，深拷贝带上这一位")
    void saveSlotDefaultsToEmptyAndCopies() {
        PlayerSave fresh = new PlayerSave();
        assertThat(fresh.giftPopup()).as("一次都没弹过就是 empty()，不是 null").isEqualTo(PlayerGiftPopup.empty());

        PlayerSave withShows = new PlayerSave();
        withShows.setGiftPopup(PlayerGiftPopup.empty().withShown("popup_defeat_relief", 123L));
        assertThat(withShows.copy().giftPopup().showsOf("popup_defeat_relief"))
                .as("copy() 走 restore() 那条路，少传一位就会在这里丢").containsExactly(123L);
    }
}
