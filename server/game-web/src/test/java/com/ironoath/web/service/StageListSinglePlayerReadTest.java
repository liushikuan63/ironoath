package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：关卡列表（{@code GET /stage/list}）这条路上，<b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：{@code StageAppService.list} 读到存档后只用了 {@code save.cityLevel()}，
 * 而算体力余额时又走 {@code PlayerWallet.available(playerId, …)} —— 那里会**再读一遍整份存档**，
 * 只为拿一个资源余额。两次之间没有玩家档写入（{@code available} 本身不落库）
 * ⇒ 第二次读把资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合白搬回来。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么这次能省</b>：{@code list} 手里已经拿着那份档 ⇒ 走
 * {@code PlayerWallet.available(PlayerSave, …)} 这一只读重载（与原版逐字同口径、同样不落库）。
 * ⚠ 只对只读成立：{@code deduct} / {@code grant} 会整篇 {@code save}，那条路上传旧档会回滚并发写手的字段。
 *
 * <p>反证：把 list 的调用改回 {@code wallet.available(playerId, …)} ⇒ 判据立刻红。
 */
class StageListSinglePlayerReadTest {

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
    @DisplayName("关卡列表：体力余额用已读到的那份存档，不再读第二遍")
    void stageListReadsThePlayerDocumentOnce() throws Exception {
        Path stage = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/StageAppService.java");
        Path wallet = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/reward/PlayerWallet.java");
        String stageText = Files.readString(stage);
        String walletText = Files.readString(wallet);

        assertThat(stageText)
                .as("关卡列表必须把已读到的存档交给钱包算余额")
                .contains("wallet.available(save, StaminaService.RESOURCE_ID, now)");

        assertThat(stageText)
                .as("关卡列表不得再让钱包自己读一遍档案")
                .doesNotContain("wallet.available(playerId, StaminaService.RESOURCE_ID, now);\n        return new StageListResp");

        assertThat(stageText)
                .as("钱包必须按具体类注入，才能调那个只读重载")
                .contains("PlayerWallet wallet;");

        int start = walletText.indexOf("public long available(PlayerSave save, String resourceType, long now) {");
        assertThat(start).as("钱包必须有「已知存档」那版").isGreaterThan(0);
        int end = walletText.indexOf("\n    }", start);
        String body = walletText.substring(start, end < 0 ? walletText.length() : end);
        assertThat(body)
                .as("那版不得自己再读档案")
                .doesNotContain("findByPlayerId")
                .doesNotContain("require(");
    }
}
