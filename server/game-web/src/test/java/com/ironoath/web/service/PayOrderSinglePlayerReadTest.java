package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：下一单（{@code POST /pay/order}）这条路上，<b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：可买判定读一次整份存档（只要 {@code paid}），礼包那道闸又读一次
 * （只要 {@code giftPopup}），而两次之间**没有任何玩家档写入** ⇒ 第二次读把整篇文档白搬回来
 * （{@code findByPlayerId} 在 Mongo 侧**无字段投影** ⇒ 一次就是整篇 + 一次反序列化）。
 *
 * <p><b>哪些读不能动</b>：记账那次（{@code recordGiftPurchaseIfAny}）是<b>写路径</b>自己的新鲜读
 * —— 它随后整篇写回，而整篇写回会把并发写手的字段一起覆盖 ⇒ 传旧档是事故。
 * 拦截器里那次在写之后、且在另一把锁里 ⇒ 同样必要。判据因此只钉「只读那两处共用一份」。
 *
 * <p><b>语义为何不变</b>：可买判定读不到档 = 当「没有已购记录」放行；礼包那道闸读不到档 = 报「玩家不存在」——
 * 而它<b>只在礼包那一支</b>被走到（非礼包提前 return）⇒ 今天给不存在的玩家下非礼包单仍会成功。
 *
 * <p>反证：把礼包那道闸改回「自己再读一遍」⇒ 判据立刻红。
 */
class PayOrderSinglePlayerReadTest {

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("scripts").resolve("check.sh"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（含 scripts/check.sh）");
    }

    @Test
    @DisplayName("下一单：礼包那道闸复用已读到的那份存档，不再读第二遍")
    void payOrderReadsThePlayerDocumentOnce() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/PayAppService.java");
        String text = Files.readString(source);

        int start = text.indexOf("private PlayerGiftPopup giftStateOf(");
        assertThat(start).as("必须找到 giftStateOf").isGreaterThan(0);
        int end = text.indexOf("\n    }", start);
        String body = text.substring(start, end < 0 ? text.length() : end);

        assertThat(body)
                .as("礼包那道闸不得自己再读整份档案")
                .doesNotContain("findByPlayerId");

        assertThat(body)
                .as("缺档仍报「玩家不存在」——不能为了省一次读把这条语义改掉")
                .contains("PLAYER_NOT_FOUND");

        assertThat(body)
                .as("它必须收下调用方已经读到的那份存档")
                .contains("PlayerSave knownSave");

        assertThat(text)
                .as("可买判定的结果要带着那份存档给下一道闸")
                .contains("private record Buyable(PayProductCfg product, PlayerSave save)");
    }
}
