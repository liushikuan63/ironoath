package com.ironoath.web.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：个人榜那一页，<b>玩家的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：榜上每一条的个人名都要一个昵称，而取昵称走的是
 * {@code players.findByPlayerId(entry.id())} —— 每次搬回**整份**存档
 * （资源表 / PVP 账本 / 科技 / 礼包弹本 / 头像框集合），而这一列只要一个昵称。
 * 一页 N 条就是 N 次整份读取。
 *
 * <p><b>改成什么</b>：整页一次**投影批读**（{@code findBriefs}），逐条从投影里取昵称。
 * 口径逐字不变：读不到档、或昵称为空 ⇒ 同一个回退（榜上上报那一刻的名字，或 id）。
 *
 * <p>反证：把显示名改回「每条自己点查」⇒ 判据立刻红。
 */
class RankBoardProjectionReadTest {

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
    @DisplayName("个人榜：整页一次投影批读，不逐条点查整份档案")
    void rankBoardUsesOneProjectedBatchRead() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/rank/RankBoardService.java");
        String text = Files.readString(source);

        int dstart = text.indexOf("private String displayNameOf(");
        assertThat(dstart).as("必须找到 displayNameOf").isGreaterThan(0);
        int dend = text.indexOf("\n    }", dstart);
        String dbody = text.substring(dstart, dend < 0 ? text.length() : dend);

        assertThat(dbody)
                .as("显示名不得逐条点查整份档案")
                .doesNotContain("players.findByPlayerId");

        assertThat(dbody)
                .as("昵称从这一页的投影里取")
                .contains("brief.nickName()");

        assertThat(dbody)
                .as("口径不变：读不到档或昵称为空 ⇒ 同一个回退")
                .contains("entry.name() == null ? entry.id() : entry.name()");

        assertThat(text)
                .as("这一页必须先做一次投影批读")
                .contains("players.findBriefs(ids)");

        assertThat(text)
                .as("组织榜不读玩家档（名字在榜上）⇒ 不该批读")
                .contains("if (type == RankType.ALLIANCE || type == RankType.NATION) {\n            return Map.of();");
    }
}
