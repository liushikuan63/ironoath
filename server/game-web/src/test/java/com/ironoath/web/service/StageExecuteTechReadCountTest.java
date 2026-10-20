package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：关卡战斗装配（{@code StageAppService.execute}）里，<b>玩家的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：战斗装配要两处个人科技 ——
 * 医院容量的科技那半（{@code hospitalCapacity}）与攻方乘区 B（{@code techBonuses.forPlayer}）——
 * 而两处各自去读了一遍整份存档，<b>都只为了拿 tech</b>，且它们之间没有任何写。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么这次能省</b>：两处要的字段是同一个（{@code tech}），装配过程里玩家档不会被改
 * （只读 heroes / cities / armies 三个集合）⇒ 读一次即可。
 *
 * <p>反证：把装配里的任一处改回「让对方自己读档」⇒ 判据立刻红。
 */
class StageExecuteTechReadCountTest {

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
    @DisplayName("战斗装配：玩家档只读一次，医院容量与个人科技共用这一份")
    void executeReadsThePlayerDocumentOnce() throws Exception {
        Path stage = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/StageAppService.java");
        Path army = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/ArmyAppService.java");
        Path bonus = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/battle/BattleTechBonuses.java");
        String stageText = Files.readString(stage);
        String armyText = Files.readString(army);
        String bonusText = Files.readString(bonus);

        // 只看 execute 方法体
        int start = stageText.indexOf("public Attempt execute(");
        assertThat(start).as("必须找到 execute").isGreaterThan(0);
        int end = stageText.indexOf("\n    }", start);
        String body = stageText.substring(start, end < 0 ? stageText.length() : end);

        assertThat(body)
                .as("医院容量必须用已读到的那份存档")
                .contains("armyAppService.hospitalCapacity(playerId, city, player)");
        assertThat(body)
                .as("个人科技必须用已读到的那份存档")
                .contains("techBonuses.forPlayer(playerId, player == null ? null : player.tech())");
        assertThat(body)
                .as("装配过程不得再让这两处自己读档")
                .doesNotContain("armyAppService.hospitalCapacity(playerId, city)")
                .doesNotContain("techBonuses.forPlayer(playerId)");

        assertThat(armyText)
                .as("必须保留「已知存档」那版入口")
                .contains("public long hospitalCapacity(String playerId, CityState city, PlayerSave save)");

        int bstart = bonusText.indexOf("public TechBonus forPlayer(String playerId, com.ironoath.core.player.PlayerTech tech) {");
        assertThat(bstart).as("必须有「已知科技」那版").isGreaterThan(0);
        int bend = bonusText.indexOf("\n    }", bstart);
        String bbody = bonusText.substring(bstart, bend < 0 ? bonusText.length() : bend);
        assertThat(bbody)
                .as("已知科技那版不得自己再读档案")
                .doesNotContain("findByPlayerId");
    }
}
