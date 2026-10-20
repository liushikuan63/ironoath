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
    @DisplayName("战斗装配：自读版自己读一份，收档版用调用方给的那份")
    void executeReadsThePlayerDocumentOnce() throws Exception {
        Path stage = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/StageAppService.java");
        String text = Files.readString(stage);

        // 收档版（调用方已经读过）：不得自己再读
        int kstart = text.indexOf("public Attempt execute(StageCfg stage, String playerId, Map<String, Long> units,\n"
                + "                           List<String> heroIds, long seed, long now, PlayerSave knownSave) {");
        assertThat(kstart).as("必须存在「收档」那版").isGreaterThan(0);
        int kend = text.indexOf("\n    }", kstart);
        String kbody = text.substring(kstart, kend < 0 ? text.length() : kend);
        // ⚠ 精确到**玩家档**那个仓储：方法体里还有 heroes.findByPlayerId（英雄名册）
        // 与 cities.findByPlayerId（城建）—— 断言写成 findByPlayerId 会把这两个也算进去。
        assertThat(kbody)
                .as("收档那版不得自己再读整份**玩家**档案")
                .doesNotContain("players.findByPlayerId");
        assertThat(kbody)
                .as("医院容量的科技那半与个人科技都用调用方给的那份")
                .contains("armyAppService.hospitalCapacity(playerId, city, knownSave)")
                .contains("techBonuses.forPlayer(playerId, knownSave == null ? null : knownSave.tech())");

        // 自读版（没有存档可给的调用方，例如扫荡）：仍要自己读一次
        int sstart = text.indexOf("public Attempt execute(StageCfg stage, String playerId, Map<String, Long> units,\n"
                + "                           List<String> heroIds, long seed, long now) {");
        assertThat(sstart).as("必须保留「自读」那版").isGreaterThan(0);
        int send = text.indexOf("\n    }", sstart);
        String sbody = text.substring(sstart, send < 0 ? text.length() : send);
        assertThat(sbody)
                .as("自读那版要自己读一次（读不到即 null，与改之前同义）")
                .contains("findByPlayerId");
    }
}
