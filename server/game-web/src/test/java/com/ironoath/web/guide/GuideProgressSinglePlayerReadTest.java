package com.ironoath.web.guide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：引导推进（{@code POST /guide/progress}）这条路上，<b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：{@code advance} 开头 {@code requireSave} 已经读到整份存档，
 * 而「这一步的达成条件」在 {@code MANUAL_RESOURCES_AND_CLAIMED} 那一支里<b>又读了一遍</b>，
 * 只为看「手动领取的资源是否已领」这一个集合。两次之间没有玩家档写入
 * ⇒ 第二次读把资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合白搬回来。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么这次能省</b>：{@code advance} 手里那份存档随后就是落库的那份
 * （推进成功后 {@code players.save(save)}）⇒ 复用它与原行为一致。
 *
 * <p>反证：把达成条件那一支改回「自己再读一遍」⇒ 判据立刻红。
 */
class GuideProgressSinglePlayerReadTest {

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
    @DisplayName("引导推进：达成条件复用已读到的那份存档，不再读第二遍")
    void guideProgressReadsThePlayerDocumentOnce() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/guide/GuideAppService.java");
        String text = Files.readString(source);

        int start = text.indexOf("private boolean judgeReached(");
        assertThat(start).as("必须找到 judgeReached").isGreaterThan(0);
        int end = text.indexOf("\n    }", start);
        String body = text.substring(start, end < 0 ? text.length() : end);

        assertThat(body)
                .as("达成条件不得自己再读整份档案")
                .doesNotContain("findByPlayerId");

        assertThat(body)
                .as("它必须收调用方已经拿到的存档")
                .contains("PlayerSave knownSave");

        // ⚠ 钉语义（那份存档的「手动领取」集合），不钉写法组合：
        // 实现可以是 knownSave.manuallyCollected()，也可以是 Optional.of(knownSave).map(...)。
        assertThat(body)
                .as("「手动领取是否已领」要看的是那份存档里的集合")
                .contains("manuallyCollected()")
                .contains("knownSave");
    }
}
